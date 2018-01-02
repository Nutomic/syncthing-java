/* 
 * Copyright (C) 2016 Davide Imbriaco
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.syncthing.java.bep;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import net.syncthing.java.core.beans.FileInfo;
import net.syncthing.java.core.interfaces.IndexRepository;
import net.syncthing.java.core.utils.ExecutorUtils;
import net.syncthing.java.core.utils.PathUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import static net.syncthing.java.core.utils.FileInfoOrdering.ALPHA_ASC_DIR_FIRST;
import static net.syncthing.java.core.utils.PathUtils.*;

/**
 *
 * @author aleph
 */
public final class IndexBrowser implements Closeable {

    public interface OnPathChangedListener {
        public void onPathChanged();
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final LoadingCache<String, List<FileInfo>> listFolderCache = CacheBuilder.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        //        .weigher(new Weigher<String, List<FileInfo>>() {
        //            @Override
        //            public int weigh(String key, List<FileInfo> list) {
        //                return list.size();
        //            }
        //        })
        //        .maximumSize(1000)
        .build(new CacheLoader<String, List<FileInfo>>() {
            @Override
            public List<FileInfo> load(String path) throws Exception {
                return doListFiles(path);
            }

        });
    private final LoadingCache<String, FileInfo> fileInfoCache = CacheBuilder.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        //        .maximumSize(1000)
        //        .weigher(new Weigher<String, FileInfo>() {
        //            @Override
        //            public int weigh(String key, FileInfo fileInfo) {
        //                return fileInfo.getBlocks().size();
        //            }
        //        })
        .build(new CacheLoader<String, FileInfo>() {
            @Override
            public FileInfo load(String path) throws Exception {
                return doGetFileInfoByAbsolutePath(path);
            }
        });

    private final String folder;
    private final IndexRepository indexRepository;
    private final IndexHandler indexHandler;
    private String currentPath;
    private final boolean includeParentInList, allowParentInRoot;
    private final FileInfo PARENT_FILE_INFO, ROOT_FILE_INFO;
    private Comparator<FileInfo> ordering;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private final Object indexHandlerEventListener = new Object() {
        @Subscribe
        public void handleIndexChangedEvent(IndexHandler.IndexChangedEvent event) {
            if (equal(event.getFolder(), folder)) {
                invalidateCache();
            }
        }

    };
    private final Set<String> preloadJobs = Sets.newLinkedHashSet();
    private final Object preloadJobsLock = new Object();
    private OnPathChangedListener mOnPathChangedListener;

    private IndexBrowser(IndexRepository indexRepository, IndexHandler indexHandler, String folder, boolean includeParentInList, boolean allowParentInRoot, Comparator<FileInfo> ordering) {
        checkNotNull(indexRepository);
        checkNotNull(indexHandler);
        checkNotNull(emptyToNull(folder));
        this.indexRepository = indexRepository;
        this.indexHandler = indexHandler;
        this.indexHandler.getEventBus().register(indexHandlerEventListener);
        this.folder = folder;
        this.includeParentInList = includeParentInList;
        this.allowParentInRoot = allowParentInRoot;
        this.ordering = ordering;
        PARENT_FILE_INFO = new FileInfo.Builder()
            .setFolder(folder)
            .setTypeDir()
            .setPath(PARENT_PATH)
            .build();
        ROOT_FILE_INFO = new FileInfo.Builder()
            .setFolder(folder)
            .setTypeDir()
            .setPath(ROOT_PATH)
            .build();
        this.currentPath = ROOT_PATH;
        executorService.scheduleWithFixedDelay(() -> {
            logger.debug("folder cache cleanup");
            listFolderCache.cleanUp();
            fileInfoCache.cleanUp();
        }, 1, 1, TimeUnit.MINUTES);
    }

    public void setOnFolderChangedListener(OnPathChangedListener onPathChangedListener) {
        mOnPathChangedListener = onPathChangedListener;
    }

    private void invalidateCache() {
        listFolderCache.invalidateAll();
        fileInfoCache.invalidateAll();
        preloadFileInfoForCurrentPath();
    }

    private void preloadFileInfoForCurrentPath() {
        logger.debug("trigger preload for folder = '{}'", folder);
        synchronized (preloadJobsLock) {
            if (preloadJobs.contains(currentPath)) {
                preloadJobs.remove(currentPath);
                preloadJobs.add(currentPath); ///add last
            } else {
                preloadJobs.add(currentPath);
                executorService.submit(new Runnable() {

                    @Override
                    public void run() {

                        String preloadPath;
                        synchronized (preloadJobsLock) {
                            checkArgument(!preloadJobs.isEmpty());
                            preloadPath = Iterables.getLast(preloadJobs); //pop last job
                        }

                        logger.info("folder preload BEGIN for folder = '{}' path = '{}'", folder, preloadPath);
                        getFileInfoByAbsolutePath(preloadPath);
                        if (!PathUtils.isRoot(preloadPath)) {
                            String parent = getParentPath(preloadPath);
                            getFileInfoByAbsolutePath(parent);
                            listFiles(parent);
                        }
                        for (FileInfo record : listFiles(preloadPath)) {
                            if (!equal(record.getPath(), PARENT_FILE_INFO.getPath()) && record.isDirectory()) {
                                listFiles(record.getPath());
                            }
                        }
                        logger.info("folder preload END for folder = '{}' path = '{}'", folder, preloadPath);
                        synchronized (preloadJobsLock) {
                            preloadJobs.remove(preloadPath);
                            if (isCacheReady()) {
                                logger.info("cache ready, notify listeners");
                                if (mOnPathChangedListener != null) {
                                    mOnPathChangedListener.onPathChanged();
                                }
                            } else {
                                logger.info("still {} job[s] left in cache loader", preloadJobs.size());
                                executorService.submit(this);
                            }
                        }
                    }

                });
            }

        }
    }

    private boolean isCacheReady() {
        synchronized (preloadJobsLock) {
            return preloadJobs.isEmpty();
        }
    }

    public String getFolder() {
        return folder;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public FileInfo getCurrentPathInfo() {
        return getFileInfoByAbsolutePath(getCurrentPath());
    }

    public String getCurrentPathFileName() {
        return PathUtils.getFileName(getCurrentPath());
    }

    public IndexBrowser setOrdering(Comparator<FileInfo> ordering) {
        checkNotNull(ordering);
        this.ordering = ordering;
        //re-sort all data in cache
        for (Map.Entry<String, List<FileInfo>> entry : Lists.newArrayList(listFolderCache.asMap().entrySet())) {
            List<FileInfo> res = Lists.newArrayList(entry.getValue());
            Collections.sort(res, IndexBrowser.this.ordering);
            listFolderCache.put(entry.getKey(), res);
        }
        return this;
    }

    public List<FileInfo> listFiles() {
        return listFiles(currentPath);
    }

    public List<FileInfo> listFiles(String absoluteDirPath) {
        logger.debug("listFiles for path = '{}'", absoluteDirPath);
        return listFolderCache.getUnchecked(absoluteDirPath);
    }

    private List<FileInfo> doListFiles(String path) {
        logger.debug("doListFiles for path = '{}' BEGIN", path);
        List<FileInfo> list = indexRepository.findNotDeletedFilesByFolderAndParent(folder, path);
        logger.debug("doListFiles for path = '{}' : {} records loaded)", path, list.size());
        for (FileInfo fileInfo : list) {
            fileInfoCache.put(fileInfo.getPath(), fileInfo);
        }
        Collections.sort(list, ordering);
        if (includeParentInList && (!PathUtils.isRoot(path) || allowParentInRoot)) {
            list.add(0, PARENT_FILE_INFO);
        }
        return Collections.unmodifiableList(list);
    }

    public boolean isRoot() {
        return PathUtils.isRoot(currentPath);
    }

    public FileInfo getFileInfoByAbsolutePath(String path) {
        return PathUtils.isRoot(path) ? ROOT_FILE_INFO : fileInfoCache.getUnchecked(path);
    }

    private FileInfo doGetFileInfoByAbsolutePath(String path) {
        logger.debug("doGetFileInfoByAbsolutePath for path = '{}' BEGIN", path);
        FileInfo fileInfo = indexRepository.findNotDeletedFileInfo(folder, path);
        checkNotNull(fileInfo, "file not found for path = %s", path);
        logger.debug("doGetFileInfoByAbsolutePath for path = '{}' END", path);
        return fileInfo;
    }

    private String getAbsolutePath(String relativePath) {
        if (equal(PARENT_PATH, relativePath)) {
            return getParentPath(currentPath);
        } else {
            return normalizePath(currentPath + PATH_SEPARATOR + relativePath);
        }
    }

    public IndexBrowser navigateTo(FileInfo fileInfo) {
        checkArgument(fileInfo.isDirectory());
        checkArgument(equal(fileInfo.getFolder(), folder));
        return equal(fileInfo.getPath(), PARENT_FILE_INFO.getPath())
                ? navigateToAbsolutePath(getParentPath(currentPath))
                : navigateToAbsolutePath(fileInfo.getPath());
    }

    public IndexBrowser navigateToNearestPath(@Nullable String oldPath) {
        if (!StringUtils.isBlank(oldPath)) {
            return navigateToAbsolutePath(oldPath);
        }
        return this;
    }

    public IndexBrowser navigateToAbsolutePath(String newPath) {
        if (PathUtils.isRoot(newPath)) {
            currentPath = ROOT_PATH;
        } else {
            FileInfo fileInfo = getFileInfoByAbsolutePath(newPath);
            checkNotNull(fileInfo, "path %s does not exist", getAbsolutePath(newPath));
            checkArgument(fileInfo.isDirectory(), "cannot navigate to path %s: not a directory", fileInfo.getPath());
            currentPath = fileInfo.getPath();
        }
        logger.info("navigate to path = '{}'", currentPath);
        preloadFileInfoForCurrentPath();
        return this;
    }

    @Override
    public void close() {
        logger.info("closing");
        this.indexHandler.getEventBus().unregister(indexHandlerEventListener);
        executorService.shutdown();
        ExecutorUtils.awaitTerminationSafe(executorService);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {

        private String folder;
        private IndexRepository indexRepository;
        private IndexHandler indexHandler;
        private boolean includeParentInList, allowParentInRoot;
        private Comparator<FileInfo> ordering = ALPHA_ASC_DIR_FIRST;

        private Builder() {

        }

        public String getFolder() {
            return folder;
        }

        public Builder setFolder(String folder) {
            this.folder = folder;
            return this;
        }

        public IndexRepository getIndexRepository() {
            return indexRepository;
        }

        public Builder setIndexRepository(IndexRepository indexRepository) {
            this.indexRepository = indexRepository;
            return this;
        }

        public IndexHandler getIndexHandler() {
            return indexHandler;
        }

        public Builder setIndexHandler(IndexHandler indexHandler) {
            this.indexHandler = indexHandler;
            return this;
        }

        public boolean doIncludeParentInList() {
            return includeParentInList;
        }

        public Builder includeParentInList(boolean includeParentInList) {
            this.includeParentInList = includeParentInList;
            return this;
        }

        public boolean doAllowParentInRoot() {
            return allowParentInRoot;
        }

        public Builder allowParentInRoot(boolean allowParentInRoot) {
            this.allowParentInRoot = allowParentInRoot;
            return this;
        }

        public Comparator<FileInfo> getOrdering() {
            return ordering;
        }

        public Builder setOrdering(Comparator<FileInfo> ordering) {
            checkNotNull(ordering);
            this.ordering = ordering;
            return this;
        }

        public IndexBrowser build() {
            return buildToAbsolutePath(ROOT_PATH);
        }

        public IndexBrowser buildToNearestPath(@Nullable String oldPath) {
            return new IndexBrowser(indexRepository, indexHandler, folder, includeParentInList, allowParentInRoot, ordering).navigateToNearestPath(oldPath);
        }

        public IndexBrowser buildToAbsolutePath(String absolutePath) {
            return new IndexBrowser(indexRepository, indexHandler, folder, includeParentInList, allowParentInRoot, ordering).navigateToAbsolutePath(absolutePath);
        }
    }
}
