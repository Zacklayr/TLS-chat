# TLS Chat

Учебный проект: защищённый корпоративный чат с шифрованием TLS.
1 сделал uml (примерный)
2 сделал прототип для презентаций (дизайн без бэка)
3 подключил git к vsc сделал клон репозитория и связал с github

## Архитектура

```mermaid
classDiagram
    class ChatServer
    class TlsSocketGateway
    class WebGateway
    class ClientConnection
    class AuthService
    class MessageService
    class EventBus
    class SessionStore
    class DesktopClient
    class WebPage

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

    DesktopClient --> TlsSocketGateway : "TLS over TCP"
    WebPage --> WebGateway : "HTTPS"
```
