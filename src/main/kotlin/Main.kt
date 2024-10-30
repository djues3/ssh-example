package io.djues3

import java.io.EOFException
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors


fun printByteArray(buffer: ByteBuffer) {
    val byteArray = buffer.array()
    println(byteArray.joinToString(" ") { "0x%02X".format(it) })
}


fun printUsage() {
    println(
        """
            Usage: ssh-example <socket-path> <file-path>
            Example: ssh-example /tmp/ssh-example ~/example.txt
            Note: Running the program will delete the socket file, if it exists.  
            """
    )
    println()
}


fun main(args: Array<String>) {
    runServer(args)
}

private fun runServer(arguments: Array<String>) {

    if (arguments.size != 2) {
        printUsage()
        return
    }

    val args = Arguments(arguments[0], arguments[1])
    val address = UnixDomainSocketAddress.of(args.socketPath)



    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->

            serverChannel.bind(address)
            println("Server listening on $address")
            while (true) {
                serverChannel.accept().use { clientChannel ->
                    executor.submit {
                        println("Connection received.")
                        val message = MessageProtocol.readMessageFromChannel(clientChannel)
                        val temp = "AAA_AAA_"
                        val okMsg = MessageProtocol.serializeMessage(Message.Write(temp.length, temp))
                        printByteArray(okMsg)

                        val bytesWritten = clientChannel.write(okMsg.flip())
                        println("Bytes written: $bytesWritten")
                    }
                }
            }

        }
    }
}

fun tryDelete(filename: String) {
    fun deleteFileIfExists(filename: String): Boolean {
        val file = File(filename)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
    if (deleteFileIfExists(filename)) {
        println("File deleted.")
    }
}

data class Arguments(val socketPath: String, val filePath: String)

sealed interface Message {

    val type: Int
    val contentLength: Int

    class Ok() : Message {
        override val type: Int
            get() = 0x1
        override val contentLength: Int
            get() = 0
    }

    data class Write(override val contentLength: Int, val content: String? = null) : Message {
        override val type: Int
            get() = 0x2
    }

    class Clear() : Message {
        override val type: Int
            get() = 0x3
        override val contentLength: Int
            get() = 0
    }

    data class Error(override val contentLength: Int, val content: String? = null) : Message {
        override val type: Int
            get() = 0x4
    }

    class Ping() : Message {
        override val type: Int
            get() = 0x5
        override val contentLength: Int
            get() = 0
    }


}

object MessageProtocol {
    private const val HEADER_SIZE = 8
    private val RESERVED = ByteArray(3)


    fun serializeMessage(message: Message): ByteBuffer {
        val content = when (message) {
            is Message.Clear, is Message.Ok, is Message.Ping -> null

            is Message.Error -> message.content
            is Message.Write -> message.content
        }
        val buf = ByteBuffer.allocate(HEADER_SIZE + message.contentLength)
        buf.put(message.type.toByte())
        buf.put(RESERVED)
        buf.putInt(message.contentLength)
        if (content != null) buf.put(content.toByteArray())
        return buf
    }

    fun deserializeWithContent(type: Int, contentLength: Int, contentBuffer: ByteBuffer): Message {
        val content = StandardCharsets.UTF_8.decode(contentBuffer).toString()

        return when (type) {
            2 -> Message.Write(contentLength, content)
            4 -> Message.Error(contentLength, content)
            else -> throw
            // @formatter:off
                IllegalArgumentException("Unknown message type: $type with content length: $contentLength received")
            // @formatter:on
        }
    }

    private fun deserializeEmptyMessage(type: Int): Message {
        return when (type) {
            1 -> Message.Ok()
            2 -> Message.Write(0)
            3 -> Message.Clear()
            4 -> Message.Error(0)
            5 -> Message.Ping()
            else -> throw IllegalArgumentException("Unknown message type: $type received")
        }
    }

    fun readMessageFromChannel(channel: SocketChannel): Message {
        val headerBuffer = ByteBuffer.allocate(HEADER_SIZE)
        while (headerBuffer.hasRemaining()) {
            if (channel.read(headerBuffer) == 1) throw EOFException("Channel closed unexpectedly")
        }
        headerBuffer.flip()
        val type = headerBuffer.get().toInt()
        // Skip three bytes
        (1..RESERVED.size).forEach {
            headerBuffer.get()
        }
        // Always read contentLength as big endian.
        val order = headerBuffer.order()
        headerBuffer.order(ByteOrder.BIG_ENDIAN)
        val contentLength = headerBuffer.getInt(4)
        headerBuffer.order(order)
        if (contentLength == 0) {
            return deserializeEmptyMessage(type = type)
        }

        val contentBuffer = ByteBuffer.allocate(contentLength)
        while (contentBuffer.hasRemaining()) {
            if (channel.read(contentBuffer) == 1) throw EOFException("Channel closed unexpectedly")
        }
        contentBuffer.flip()
        return deserializeWithContent(
            type = type, contentLength = contentLength, contentBuffer = contentBuffer
        )
    }


}


