# TLS-chat
ну короче пока вот здесь буду писать когда что и зач добавил 

classDiagram
    ChatServer *-- TlsSocketGateway
    ChatServer *-- WebGateway
    ChatServer *-- AuthService
    ChatServer *-- MessageService
    ChatServer *-- EventBus
    TlsSocketGateway --> ClientConnection
    ClientConnection --> AuthService
    ClientConnection --> MessageService
    WebGateway --> AuthService
    WebGateway --> MessageService
    AuthService --> SessionStore
    MessageService --> EventBus
    DesktopClient --> TlsSocketGateway : TLS (TCP)
    WebPage --> WebGateway : HTTPS

