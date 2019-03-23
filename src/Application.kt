package com.sample

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.install
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.defaultResource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.sessions.sessions
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.WebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val SESSION_REGISTER_NAME = "ktor-chat-sample"

inline val WebSocketServerSession.session: UserSession? get() = try { call.sessions.get(SESSION_REGISTER_NAME) as? UserSession } catch (th: Throwable) { null }
inline val PipelineContext<*, ApplicationCall>.session: UserSession? get() = try { context.sessions.get(SESSION_REGISTER_NAME) as? UserSession } catch (th: Throwable) { null }
fun PipelineContext<*, ApplicationCall>.setSession(us: UserSession) = context.sessions.set(SESSION_REGISTER_NAME, us)

private val server = ChatServer()

@UseExperimental(ObsoleteCoroutinesApi::class)
fun Application.main() {
    install(WebSockets) {
        pingPeriod = Duration.ofMinutes(1)
    }
    install(Sessions) {
        cookie<UserSession>(SESSION_REGISTER_NAME) {
            cookie.path = "/"
        }
    }
    routing {
        resources("web")
        static {
            get {
                val ses = session
                if (ses == null) {
                    setSession(UserSession(UUID.randomUUID().toString(), "tmpNickname", "0"))
                }
            }
            defaultResource("index.html", "web")
        }
        webSocket("/ws") {
            val ses = session
            if (ses == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
                return@webSocket
            }
            server.memberJoin(ses, this)
            try {
                incoming.consumeEach {
                    if (it is Frame.Text) {
                        server.receivedMessage(ses, it.readText())
                    }
                }
            } finally {
                server.memberLeft(ses, this)
            }
        }

    }
}


class ChatServer {
    private val memberNames = ConcurrentHashMap<UserSession, String>()
    private val members = ConcurrentHashMap<UserSession, MutableList<WebSocketSession>>()
    private val lastMessages = LinkedList<String>()

    suspend fun memberJoin(member: UserSession, socket: WebSocketSession) {
        val name = memberNames.computeIfAbsent(member) { member.nickname }
        val list = members.computeIfAbsent(member) { CopyOnWriteArrayList<WebSocketSession>() }
        list.add(socket)
        if (list.size == 1) {
            serverBroadcast("Member joined: $name.")
        }
        val messages = synchronized(lastMessages) { lastMessages.toList() }
        for (message in messages) {
            socket.send(Frame.Text(message))
        }
    }

    suspend fun memberRenamed(member: UserSession, to: String) {
        val oldName = memberNames.put(member, to) ?: member.nickname
        serverBroadcast("Member renamed from $oldName to $to")
    }

    suspend fun memberLeft(member: UserSession, socket: WebSocketSession) {
        val connections = members[member]
        connections?.remove(socket)
        if (connections != null && connections.isEmpty()) {
            val name = memberNames.remove(member) ?: member
            serverBroadcast("Member left: $name.")
        }
    }

    suspend fun sendTo(recipient: UserSession, sender: String, message: String) =
        members[recipient]?.send(Frame.Text("[$sender] $message"))

    suspend fun message(sender: UserSession, message: String) {
        val name = memberNames[sender] ?: sender.nickname
        val formatted = "[$name] $message"
        broadcast(sender.chatroomId, formatted)
        synchronized(lastMessages) {
            lastMessages.add(formatted)
            if (lastMessages.size > 100) {
                lastMessages.removeFirst()
            }
        }
    }

    private suspend fun broadcast(roomId: String, message: String) =
        members.filter { it.key.chatroomId == roomId }.values.forEach {
            it.send(Frame.Text(message))
        }


    private suspend fun serverBroadcast(message: String) =
        members.values.forEach {
            it.send(Frame.Text("[server] $message"))
        }

    suspend fun List<WebSocketSession>.send(frame: Frame) =
        forEach {
            try {
                it.send(frame.copy())
            } catch (t: Throwable) {
                try {
                    it.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, ""))
                } catch (ignore: ClosedSendChannelException) {

                }
            }
        }
    suspend fun receivedMessage(id: UserSession, command: String) {
        when {
            command.startsWith("/user") -> {
                val newName = command.removePrefix("/user").trim()
                when {
                    newName.isEmpty() -> server.sendTo(id, "server::help", "/user [newName]")
                    newName.length > 50 -> server.sendTo(
                        id,
                        "server::help",
                        "new name is too long: 50 characters limit"
                    )
                    else -> server.memberRenamed(id, newName)
                }
            }
            else -> server.message(id, command)
        }
    }
}

data class UserSession(
    val uuid: String,           // UUID
    var nickname: String,       // 昵称
    var chatroomId: String      // 聊天房间id
)
