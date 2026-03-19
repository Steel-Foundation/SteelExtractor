package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.protocol.PacketType
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.network.protocol.handshake.HandshakeProtocols
import net.minecraft.network.protocol.login.LoginProtocols
import net.minecraft.network.protocol.status.StatusProtocols
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

class Packets : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-packets")

    override fun fileName(): String {
        return "steel-registry/build_assets/packets.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val packetsJson = JsonObject()

        val clientBound = arrayOf(
            StatusProtocols.CLIENTBOUND_TEMPLATE.details(),
            LoginProtocols.CLIENTBOUND_TEMPLATE.details(),
            ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details(),
            GameProtocols.CLIENTBOUND_TEMPLATE.details()
        )

        val serverBound = arrayOf(
            HandshakeProtocols.SERVERBOUND_TEMPLATE.details(),
            StatusProtocols.SERVERBOUND_TEMPLATE.details(),
            LoginProtocols.SERVERBOUND_TEMPLATE.details(),
            ConfigurationProtocols.SERVERBOUND_TEMPLATE.details(),
            GameProtocols.SERVERBOUND_TEMPLATE.details()
        )
        val serverBoundJson = serializeServerBound(serverBound)
        val clientBoundJson = serializeClientBound(clientBound)
        packetsJson.addProperty("version", SharedConstants.getProtocolVersion())
        packetsJson.add("serverbound", serverBoundJson)
        packetsJson.add("clientbound", clientBoundJson)
        return packetsJson
    }


    private fun serializeServerBound(
        packets: Array<ProtocolInfo.Details>
    ): JsonObject {
        val handshakeArray = JsonArray()
        val statusArray = JsonArray()
        val loginArray = JsonArray()
        val configArray = JsonArray()
        val playArray = JsonArray()

        for (factory in packets) {
            factory.listPackets { type: PacketType<*>, _: Int ->
                when (factory.id()!!) {
                    ConnectionProtocol.HANDSHAKING -> handshakeArray.add(type.id().path)
                    ConnectionProtocol.PLAY -> playArray.add(type.id().path)
                    ConnectionProtocol.STATUS -> statusArray.add(type.id().path)
                    ConnectionProtocol.LOGIN -> loginArray.add(type.id().path)
                    ConnectionProtocol.CONFIGURATION -> configArray.add(type.id().path)
                }
            }
        }

        val finalJson = JsonObject()
        finalJson.add("handshake", handshakeArray)
        finalJson.add("status", statusArray)
        finalJson.add("login", loginArray)
        finalJson.add("config", configArray)
        finalJson.add("play", playArray)
        return finalJson
    }

    private fun serializeClientBound(
        packets: Array<ProtocolInfo.Details>
    ): JsonObject {
        val statusArray = JsonArray()
        val loginArray = JsonArray()
        val configArray = JsonArray()
        val playArray = JsonArray()

        for (factory in packets) {
            factory.listPackets { type: PacketType<*>, _: Int ->
                when (factory.id()!!) {
                    ConnectionProtocol.HANDSHAKING -> error("Client bound Packet should have no handshake")
                    ConnectionProtocol.PLAY -> playArray.add(type.id().path)
                    ConnectionProtocol.STATUS -> statusArray.add(type.id().path)
                    ConnectionProtocol.LOGIN -> loginArray.add(type.id().path)
                    ConnectionProtocol.CONFIGURATION -> configArray.add(type.id().path)
                }
            }
        }
        val finalJson = JsonObject()
        finalJson.add("status", statusArray)
        finalJson.add("login", loginArray)
        finalJson.add("config", configArray)
        finalJson.add("play", playArray)
        return finalJson
    }
}