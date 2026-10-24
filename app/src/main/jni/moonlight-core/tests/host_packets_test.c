// Checks the packets moonlight-common-c sends a host as a stream starts, for a controller and for
// Prevent Packet Loss, through the real ControlStream.c and InputStream.c. Sunshine-family hosts
// log packets they don't know: Vibepollo logs an unknown control packet type with a hex dump at
// debug level (control_server_t::call() in its src/stream.cpp) and warns about an unknown input
// packet (validate_packet() in its src/input.cpp). Not part of the app build; build and run on
// the host from this folder with host_packets_test.sh.
//
// ControlStream.c is included, not linked, so the test can give it a pretend ENet peer. ENet,
// threads, queues and crypto are stubbed. "Encrypting" copies the data, so the test reads each
// packet the way the host reads it after decrypting.

#include "../moonlight-common-c/src/ControlStream.c"

#include <stdio.h>

// Globals of the parts of moonlight-common-c this doesn't build
int AppVersionQuad[4];
STREAM_CONFIGURATION StreamConfig;
CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;  // Limelog() is quiet without logMessage
struct sockaddr_storage RemoteAddr;
struct sockaddr_storage LocalAddr;
SOCKADDR_LEN AddrLen;
volatile bool ConnectionInterrupted;
uint32_t ControlConnectData;
uint16_t ControlPortNumber;
uint32_t EncryptionFeaturesEnabled;
uint32_t SunshineFeatureFlags;

// Threads: nothing runs by itself. The test runs the input thread with sendQueuedInput().
static ThreadEntry threadEntry;
static void* threadContext;
int PltCreateThread(const char* name, ThreadEntry entry, void* context, PLT_THREAD* thread) {
    (void)name; (void)thread;
    threadEntry = entry;
    threadContext = context;
    return 0;
}
void PltInterruptThread(PLT_THREAD* thread) { (void)thread; }
bool PltIsThreadInterrupted(PLT_THREAD* thread) { (void)thread; return false; }
void PltJoinThread(PLT_THREAD* thread) { (void)thread; }
int PltCreateMutex(PLT_MUTEX* mutex) { (void)mutex; return 0; }
void PltDeleteMutex(PLT_MUTEX* mutex) { (void)mutex; }
void PltLockMutex(PLT_MUTEX* mutex) { (void)mutex; }
void PltUnlockMutex(PLT_MUTEX* mutex) { (void)mutex; }
int PltCreateEvent(PLT_EVENT* event) { (void)event; return 0; }
void PltCloseEvent(PLT_EVENT* event) { (void)event; }
void PltSetEvent(PLT_EVENT* event) { (void)event; }
void PltClearEvent(PLT_EVENT* event) { (void)event; }
void PltWaitForEvent(PLT_EVENT* event) { (void)event; }
void PltSleepMs(int ms) { (void)ms; }
void PltSleepMsInterruptible(PLT_THREAD* thread, int ms) { (void)thread; (void)ms; }
uint64_t PltGetMillis(void) { static uint64_t t = 1000; return t += 10; }

// Queues: a plain list, since only one thread runs
int LbqInitializeLinkedBlockingQueue(PLINKED_BLOCKING_QUEUE queue, int sizeBound) {
    memset(queue, 0, sizeof(*queue));
    queue->sizeBound = sizeBound;
    return LBQ_SUCCESS;
}
int LbqOfferQueueItem(PLINKED_BLOCKING_QUEUE queue, void* data, PLINKED_BLOCKING_QUEUE_ENTRY entry) {
    if (queue->currentSize >= queue->sizeBound) {
        return LBQ_BOUND_EXCEEDED;
    }
    entry->data = data;
    entry->flink = NULL;
    entry->blink = queue->tail;
    if (queue->tail != NULL) {
        queue->tail->flink = entry;
    }
    else {
        queue->head = entry;
    }
    queue->tail = entry;
    queue->currentSize++;
    return LBQ_SUCCESS;
}
int LbqPollQueueElement(PLINKED_BLOCKING_QUEUE queue, void** data) {
    PLINKED_BLOCKING_QUEUE_ENTRY entry = queue->head;
    if (entry == NULL) {
        return LBQ_NO_ELEMENT;
    }
    queue->head = entry->flink;
    if (queue->head != NULL) {
        queue->head->blink = NULL;
    }
    else {
        queue->tail = NULL;
    }
    queue->currentSize--;
    *data = entry->data;
    return LBQ_SUCCESS;
}
// An empty queue stops a waiting thread, so the input thread returns once it has sent everything
int LbqWaitForQueueElement(PLINKED_BLOCKING_QUEUE queue, void** data) {
    return LbqPollQueueElement(queue, data) == LBQ_SUCCESS ? LBQ_SUCCESS : LBQ_INTERRUPTED;
}
int LbqPeekQueueElement(PLINKED_BLOCKING_QUEUE queue, void** data) {
    if (queue->head == NULL) {
        return LBQ_NO_ELEMENT;
    }
    *data = queue->head->data;
    return LBQ_SUCCESS;
}
PLINKED_BLOCKING_QUEUE_ENTRY LbqFlushQueueItems(PLINKED_BLOCKING_QUEUE queue) {
    PLINKED_BLOCKING_QUEUE_ENTRY head = queue->head;
    queue->head = queue->tail = NULL;
    queue->currentSize = 0;
    return head;
}
PLINKED_BLOCKING_QUEUE_ENTRY LbqDestroyLinkedBlockingQueue(PLINKED_BLOCKING_QUEUE queue) { return LbqFlushQueueItems(queue); }
void LbqSignalQueueShutdown(PLINKED_BLOCKING_QUEUE queue) { (void)queue; }
void LbqSignalQueueDrain(PLINKED_BLOCKING_QUEUE queue) { (void)queue; }
int LbqGetItemCount(PLINKED_BLOCKING_QUEUE queue) { return queue->currentSize; }

// Crypto: "encrypting" copies the data and writes an empty tag
static char cryptoContextStorage;
PPLT_CRYPTO_CONTEXT PltCreateCryptoContext(void) { return (PPLT_CRYPTO_CONTEXT)(void*)&cryptoContextStorage; }
void PltDestroyCryptoContext(PPLT_CRYPTO_CONTEXT ctx) { (void)ctx; }
bool PltEncryptMessage(PPLT_CRYPTO_CONTEXT ctx, int algorithm, int flags,
                       unsigned char* key, int keyLength,
                       unsigned char* iv, int ivLength,
                       unsigned char* tag, int tagLength,
                       unsigned char* inputData, int inputDataLength,
                       unsigned char* outputData, int* outputDataLength) {
    (void)ctx; (void)algorithm; (void)flags; (void)key; (void)keyLength; (void)iv; (void)ivLength;
    if (tag != NULL) {
        memset(tag, 0, tagLength);
    }
    memmove(outputData, inputData, inputDataLength);
    *outputDataLength = inputDataLength;
    return true;
}
bool PltDecryptMessage(PPLT_CRYPTO_CONTEXT ctx, int algorithm, int flags,
                       unsigned char* key, int keyLength,
                       unsigned char* iv, int ivLength,
                       unsigned char* tag, int tagLength,
                       unsigned char* inputData, int inputDataLength,
                       unsigned char* outputData, int* outputDataLength) {
    (void)ctx; (void)algorithm; (void)flags; (void)key; (void)keyLength; (void)iv; (void)ivLength;
    (void)tag; (void)tagLength; (void)inputData; (void)inputDataLength; (void)outputData; (void)outputDataLength;
    return false;
}

// Sockets and the rest of the connection
SOCKET connectTcpSocket(struct sockaddr_storage* dstaddr, SOCKADDR_LEN addrlen, unsigned short port, int timeoutSec) {
    (void)dstaddr; (void)addrlen; (void)port; (void)timeoutSec;
    return INVALID_SOCKET;
}
int enableNoDelay(SOCKET s) { (void)s; return 0; }
void shutdownTcpSocket(SOCKET s) { (void)s; }
void closeSocket(SOCKET s) { (void)s; }
int serviceEnetHost(ENetHost* host, ENetEvent* event, enet_uint32 timeoutMs) { (void)host; (void)event; (void)timeoutMs; return 0; }
int gracefullyDisconnectEnetPeer(ENetHost* host, ENetPeer* enetPeer, enet_uint32 lingerTimeoutMs) {
    (void)host; (void)enetPeer; (void)lingerTimeoutMs;
    return 0;
}
bool isReferenceFrameInvalidationEnabled(void) { return false; }

// ENet: every packet given to enet_peer_send() is kept in sent[] as the host would get it
#define MAX_SENT 16
static struct {
    unsigned char data[256];
    size_t length;
} sent[MAX_SENT];
static int sentCount;

ENetPacket* enet_packet_create(const void* data, size_t dataLength, enet_uint32 flags) {
    ENetPacket* packet = calloc(1, sizeof(*packet));
    packet->data = calloc(1, dataLength + 1);
    if (data != NULL) {
        memcpy(packet->data, data, dataLength);
    }
    packet->dataLength = dataLength;
    packet->flags = flags;
    return packet;
}
void enet_packet_destroy(ENetPacket* packet) {
    if (packet->freeCallback != NULL) {
        packet->freeCallback(packet);
    }
    free(packet->data);
    free(packet);
}
int enet_peer_send(ENetPeer* enetPeer, enet_uint8 channelID, ENetPacket* packet) {
    (void)enetPeer; (void)channelID;
    if (sentCount < MAX_SENT && packet->dataLength <= sizeof(sent[0].data)) {
        memcpy(sent[sentCount].data, packet->data, packet->dataLength);
        sent[sentCount].length = packet->dataLength;
    }
    sentCount++;
    enet_packet_destroy(packet);  // ENet owns a packet once it's been given one
    return 0;
}
int enet_host_service(ENetHost* host, ENetEvent* event, enet_uint32 timeout) { (void)host; (void)event; (void)timeout; return 0; }
void enet_host_flush(ENetHost* host) { (void)host; }
ENetHost* enet_host_create(int addressFamily, const ENetAddress* address, size_t peerCount, size_t channelLimit,
                           enet_uint32 incomingBandwidth, enet_uint32 outgoingBandwidth) {
    (void)addressFamily; (void)address; (void)peerCount; (void)channelLimit; (void)incomingBandwidth; (void)outgoingBandwidth;
    return NULL;
}
void enet_host_destroy(ENetHost* host) { (void)host; }
ENetPeer* enet_host_connect(ENetHost* host, const ENetAddress* address, size_t channelCount, enet_uint32 data) {
    (void)host; (void)address; (void)channelCount; (void)data;
    return NULL;
}
void enet_peer_timeout(ENetPeer* enetPeer, enet_uint32 limit, enet_uint32 minimum, enet_uint32 maximum) {
    (void)enetPeer; (void)limit; (void)minimum; (void)maximum;
}
void enet_peer_reset(ENetPeer* enetPeer) { (void)enetPeer; }
void enet_peer_disconnect_now(ENetPeer* enetPeer, enet_uint32 data) { (void)enetPeer; (void)data; }
int enet_address_set_address(ENetAddress* address, struct sockaddr* addr, socklen_t addrlen) {
    (void)address; (void)addr; (void)addrlen;
    return 0;
}
int enet_address_set_port(ENetAddress* address, enet_uint16 port) { (void)address; (void)port; return 0; }
int enet_socket_wait(ENetSocket socket, enet_uint32* condition, enet_uint32 timeout) {
    (void)socket; (void)condition; (void)timeout;
    return 0;
}
int enet_socket_set_option(ENetSocket socket, ENetSocketOption option, int value) {
    (void)socket; (void)option; (void)value;
    return 0;
}

static int failures;

static void check(bool ok, const char* what) {
    printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

static unsigned read16le(const unsigned char* p) { return p[0] | (p[1] << 8); }
static uint32_t read32le(const unsigned char* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }

// A sent packet's control message type, after the host decrypts it. Every stream here uses the
// encrypted control stream (hosts that report 7.1.431 or later).
#define CONTROL_HEADER_SIZE (sizeof(NVCTL_ENCRYPTED_PACKET_HEADER) + AES_GCM_TAG_LENGTH)
static unsigned controlType(int i) {
    return read16le(sent[i].data + CONTROL_HEADER_SIZE);
}

// How many sent packets are input packets with this magic. An input packet is the payload of an
// input data control message, and starts with its size (big endian) and then its magic.
static int countInput(uint32_t magic) {
    int count = 0;
    for (int i = 0; i < sentCount && i < MAX_SENT; i++) {
        const unsigned char* payload = sent[i].data + CONTROL_HEADER_SIZE + sizeof(NVCTL_ENET_PACKET_HEADER_V2);
        if (controlType(i) == (unsigned short)packetTypes[IDX_INPUT_DATA] &&
                read32le(payload + sizeof(uint32_t)) == magic) {
            count++;
        }
    }
    return count;
}

static ENetHost fakeHost;
static ENetPeer fakePeer;

// Starts the control and input streams the way LiStartConnection() does, for a host that reports
// this appversion, and sends what the input stream queued as it started
static void startStream(int a, int b, int c, int d) {
    AppVersionQuad[0] = a;
    AppVersionQuad[1] = b;
    AppVersionQuad[2] = c;
    AppVersionQuad[3] = d;
    sentCount = 0;

    initializeControlStream();
    memset(&fakePeer, 0, sizeof(fakePeer));
    fakePeer.channelCount = CTRL_CHANNEL_COUNT;
    peer = &fakePeer;
    client = &fakeHost;

    initializeInputStream();
    startInputStream();
    threadEntry(threadContext);
}

static void endStream(void) {
    stopInputStream();
    destroyInputStream();
    stopping = true;
    destroyControlStream();
}

int main(void) {
    printf("A Sunshine-family host (Vibepollo reports 7.1.431.-1) as the stream starts:\n");
    startStream(7, 1, 431, -1);
    check(countInput(ENABLE_HAPTICS_MAGIC) == 0, "no haptics enable packet (0x0D), which these hosts don't know");

    printf("A controller on that host:\n");
    sentCount = 0;
    LiSendControllerEvent(A_FLAG, 0, 0, 0, 0, 0, 0);
    threadEntry(threadContext);
    check(countInput(MULTI_CONTROLLER_MAGIC_GEN5) == 1, "sent with the Gen 5 magic (0x0C)");
    check(countInput(MULTI_CONTROLLER_MAGIC) == 0, "never the old magic (0x0D)");

    // Sunshine, Apollo and Vibepollo handle a periodic ping (0x0200) by doing nothing, logging it
    // only at their most detailed level. A type they don't map is logged at debug level with a
    // hex dump, and Prevent Packet Loss sends one every 20 ms.
    printf("Prevent Packet Loss on that host:\n");
    sentCount = 0;
    check(LiSendKeepAlivePayload() == 0, "sent");
    check(sentCount == 1 && controlType(0) == 0x0200, "sent as a periodic ping (0x0200), a type the host knows");
    endStream();

    printf("GeForce Experience 7.1.431 as the stream starts:\n");
    startStream(7, 1, 431, 0);
    check(countInput(ENABLE_HAPTICS_MAGIC) == 1, "still sends the haptics enable packet, so GFE sends rumble");

    printf("Prevent Packet Loss on GeForce Experience:\n");
    sentCount = 0;
    check(LiSendKeepAlivePayload() == -2 && sentCount == 0, "isn't sent");
    endStream();

    printf("GeForce Experience before 7.1 as the stream starts:\n");
    startStream(7, 0, 0, 0);
    check(sentCount == 0, "sends nothing, as these versions may drop the connection on a packet they don't know");
    endStream();

    printf(failures ? "\n%d checks failed\n" : "\nAll checks passed\n", failures);
    return failures ? 1 : 0;
}
