package net.syncthing.java.core.configuration

import com.google.gson.GsonBuilder
import net.syncthing.java.core.beans.DeviceId
import net.syncthing.java.core.beans.DeviceInfo
import net.syncthing.java.core.beans.FolderInfo
import net.syncthing.java.core.security.KeystoreHandler
import org.bouncycastle.util.encoders.Base64
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.util.*

class Configuration(configFolder: File = DefaultConfigFolder,
                    val cacheFolder: File = File(System.getProperty("java.io.tmpdir"), "syncthing_lite_cache")) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Config(
            val peers: Set<DeviceInfo>,
            val folders: Set<FolderInfo>,
            val localDeviceName: String,
            val localDeviceId: String,
            val discoveryServers: Set<String>,
            val keystoreAlgorithm: String,
            val keystoreData: String) {
        // Exclude keystoreData from toString()
        override fun toString() = "Config(peers=$peers, folders=$folders, localDeviceName=$localDeviceName, " +
                "localDeviceId=$localDeviceId, discoveryServers=$discoveryServers, keystoreAlgorithm=$keystoreAlgorithm)"
    }

    private val configFile = File(configFolder, ConfigFileName)
    val databaseFolder = File(configFolder, DatabaseFolderName)

    private var isSaved = true
    private var config: Config

    init {
        configFolder.mkdirs()
        cacheFolder.mkdirs()
        databaseFolder.mkdirs()
        assert(configFolder.isDirectory && configFile.canWrite(), { "Invalid config folder $configFolder" })
        assert(cacheFolder.isDirectory && cacheFolder.canWrite(), { "invalid cache dir $databaseFolder" })

        if (!configFile.exists()) {
            var localDeviceName = InetAddress.getLocalHost().hostName
            if (localDeviceName.isEmpty() || localDeviceName == "localhost") {
                localDeviceName = "syncthing-lite"
            }
            val keystoreData = KeystoreHandler.Loader().generateKeystore()
            isSaved = false
            config = Config(peers = setOf(), folders = setOf(),
                    localDeviceName = localDeviceName,
                    discoveryServers = Companion.DiscoveryServers,
                    localDeviceId = keystoreData.first.deviceId,
                    keystoreData = Base64.toBase64String(keystoreData.second),
                    keystoreAlgorithm = keystoreData.third)
            persistNow()
        } else {
            config = Gson.fromJson(configFile.readText(), Config::class.java)
        }
        logger.debug("Loaded config = $config")
    }

    companion object {
        private val DefaultConfigFolder = File(System.getProperty("user.home"), ".config/syncthing-java/")
        private const val ConfigFileName = "config.json"
        private const val DatabaseFolderName = "database"
        private val DiscoveryServers = setOf(
                "discovery-v4-1.syncthing.net", "discovery-v4-2.syncthing.net", "discovery-v4-3.syncthing.net",
                "discovery-v6-1.syncthing.net", "discovery-v6-2.syncthing.net", "discovery-v6-3.syncthing.net")
        private val Gson = GsonBuilder().setPrettyPrinting().create()
    }

    val instanceId = Math.abs(Random().nextLong())

    val localDeviceId: DeviceId
        get() = DeviceId(config.localDeviceId)

    val discoveryServers: Set<String>
        get() = config.discoveryServers

    val keystoreData: ByteArray
        get() = Base64.decode(config.keystoreData)

    val keystoreAlgorithm: String
        get() = config.keystoreAlgorithm

    val clientName = "syncthing-java"

    val clientVersion = javaClass.`package`.implementationVersion ?: "0.0.0"

    val peerIds: Set<DeviceId>
        get() = config.peers.map { it.deviceId }.toSet()

    var localDeviceName: String
        get() = config.localDeviceName
        set(localDeviceName) {
            config = config.copy(localDeviceName = localDeviceName)
            isSaved = false
        }

    var folders: Set<FolderInfo>
        get() = config.folders
        set(folders) {
            config = config.copy(folders = folders)
            isSaved = false
        }

    var peers: Set<DeviceInfo>
        get() = config.peers
        set(peers) {
            config = config.copy(peers = peers)
            isSaved = false
        }

    fun persistNow() {
        persist()
    }

    fun persistLater() {
        Thread { persist() }.start()
    }

    private fun persist() {
        if (isSaved)
            return

        config.let {
            System.out.println("writing config to $configFile")
            configFile.writeText(Gson.toJson(config))
            isSaved = true
        }
    }

    override fun toString() = "Configuration(peers=$peers, folders=$folders, localDeviceName=$localDeviceName, " +
            "localDeviceId=${localDeviceId.deviceId}, discoveryServers=$discoveryServers, instanceId=$instanceId, " +
            "configFile=$configFile, databaseFolder=$databaseFolder, cacheFolder=$cacheFolder)"
}