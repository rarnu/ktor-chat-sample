var socket = null;

function connect() {
    socket = new WebSocket("ws://" + window.location.host + "/ws");
    socket.onclose = function(e) {
        var explanation = "";
        if (e.reason && e.reason.length > 0) {
            explanation = "reason: " + e.reason;
        } else {
            explanation = "without a reason specified";
        }
        write("Disconnected with close code " + e.code + " and " + explanation);
        setTimeout(connect, 5000);
    };
    socket.onmessage = function(e) {
        received(e.data.toString());
    };
}

function received(message) {
    write(message);
}

function write(message) {
    var line = document.createElement("p");
    line.className = "message";
    line.textContent = message;
    var messagesDiv = $("#_chat_container")[0];
    messagesDiv.appendChild(line);
    messagesDiv.scrollTop = line.offsetTop;
}

function onSend() {
    var input = $("#_chat_text")[0];
    if (input) {
        var text = input.value;
        if (text && socket) {
            socket.send(text);
            input.value = "";
        }
    }
}

function start() {
    connect();
    $("#_btn_send_chat")[0].onclick = onSend;
    $("#_chat_text")[0].onkeypress = function(e) {
        if (e.keyCode === 13) {
            onSend();
        }
    };
}

function initLoop() {
    if ($("#_btn_send_chat")[0]) {
        start();
    } else {
        setTimeout(initLoop, 300);
    }
}

initLoop();
