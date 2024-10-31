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

fun File.clear() {
    this.writeText("")
}

// Used for testing.
private fun printByteArray(buffer: ByteBuffer) {
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
    Server().run(args)
}

class Server {

    private var file: File? = null

    fun run(arguments: Array<String>) {

        if (arguments.size != 2) {
            printUsage()
            return
        }

        val (socketPath, filePath) = arguments
        val address = UnixDomainSocketAddress.of(socketPath)

        file = File(filePath).let { file ->
            // I assume that it's intended for the "server" to create files if it doesn't already exist
            if (file.createNewFile()) {
                println("The file $file doesn't exist, it will be created for you.")
            }
            file
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                tryDelete(socketPath)
                serverChannel.bind(address)
                println("Server listening on $address")
                while (true) {
                    serverChannel.accept().use { clientChannel ->
                        executor.submit {
                            try {
                                println("Connection received.")
                                val msg = try {
                                    val msg = MessageProtocol.readMessageFromChannel(clientChannel)
                                    handleMessage(msg)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Message.Error(e.message)
                                }
                                if (msg != Message.None) {
                                    val retBuff = MessageProtocol.serializeMessage(msg)
                                    retBuff.flip()
                                    clientChannel.write(retBuff)
                                }
                            } catch (e: Exception) {
                                println("Error processing message: ${e.javaClass.simpleName} - ${e.message}")
                                e.printStackTrace()
                            }
                        }.get()
                    }
                }

            }
        }
    }

    private fun tryDelete(filename: String) {
        fun deleteFileIfExists(filename: String): Boolean {
            val file = File(filename)
            return if (file.exists()) {
                file.delete()
            } else {
                false
            }
        }
        if (deleteFileIfExists(filename)) {
            println("Socket file deleted.")
        }
    }

    /**
     *
     * */
    private fun handleMessage(message: Message): Message {
        return when (message) {
            is Message.Ping -> Message.Ok
            is Message.Write -> {
                message.content?.let { this.file?.appendText(it) }
                    ?: throw IllegalArgumentException("File cannot be null.")
                Message.Ok
            }

            is Message.Clear -> {
                try {
                    this.file?.clear()
                    Message.Ok
                } catch (e: Exception) {
                    Message.Error(e.message)
                }
            }

            else -> {
                Message.None
            }
        }
    }
}


sealed interface Message {

    val type: Int
    val contentLength: Int
    /**
     * Used as a marker for no value, instead of null.
     * */
    data object None : Message {
        override val type: Int
            get() = throw NotImplementedError("Shouldn't get called")
        override val contentLength: Int
            get() = throw NotImplementedError("Shouldn't get called")
    }

    data object Ok : Message {
        override val type: Int
            get() = 0x1
        override val contentLength: Int
            get() = 0
    }

    data class Write(val content: String? = null) : Message {
        override val type: Int
            get() = 0x2

        override val contentLength: Int
            get() = content?.length ?: 0
    }

    data object Clear : Message {
        override val type: Int
            get() = 0x3
        override val contentLength: Int
            get() = 0
    }

    data class Error(val content: String? = null) : Message {
        override val type: Int
            get() = 0x4
        override val contentLength: Int
            get() = content?.length ?: 0

    }

    data object Ping : Message {
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
            is Message.None -> throw IllegalArgumentException("Cannot serialize Message.None!")
        }

        val buf = ByteBuffer.allocate(HEADER_SIZE + message.contentLength)
        buf.put(message.type.toByte())
        buf.put(RESERVED)
        buf.putInt(message.contentLength)
        if (content != null) buf.put(content.toByteArray())
        return buf
    }

    private fun deserializeWithContent(type: Int, contentLength: Int, contentBuffer: ByteBuffer): Message {
        val content = StandardCharsets.UTF_8.decode(contentBuffer).toString()

        if (content.length != contentLength) {
            throw IllegalStateException("Length of content string and content length from header don't match.")
        }

        // @formatter:off
        return when (type) {
            2 -> Message.Write(content)
            4 -> Message.Error(content)
            // Can be replaced with Message.Error with the same text, to avoid unnecessary try / catches
            else -> throw
            IllegalArgumentException("Unknown message type: $type with content length: $contentLength received")
        }
        // @formatter:on
    }

    private fun deserializeEmptyMessage(type: Int): Message {
        return when (type) {
            1 -> Message.Ok
            2 -> Message.Write()
            3 -> Message.Clear
            4 -> Message.Error()
            5 -> Message.Ping
            else -> throw IllegalArgumentException("Unknown message type: $type received")
        }
    }

    fun readMessageFromChannel(channel: SocketChannel): Message {
        val headerBuffer = ByteBuffer.allocate(HEADER_SIZE)
        while (headerBuffer.hasRemaining()) {
            if (channel.read(headerBuffer) == -1) throw EOFException("Channel closed unexpectedly")
        }
        headerBuffer.flip()
        val type = headerBuffer.get().toInt()

        // Skip reserved bytes
        (1..RESERVED.size).forEach { _ ->
            headerBuffer.get()
        }

        // Always read contentLength as big endian.
        val order = headerBuffer.order()
        headerBuffer.order(ByteOrder.BIG_ENDIAN)
        val contentLength = headerBuffer.getInt(4)
        headerBuffer.order(order)

        if (contentLength < 0) throw IllegalArgumentException("Content length cannot be less than zero. Was: $contentLength")


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

