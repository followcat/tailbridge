/* adbstitch: park a phone-initiated TCP conn on a remote bridge and splice
 * it to a local service. Usage: adbstitch <remote_ip> <remote_port> <local_port>
 *
 * Park phase: only waits on the remote conn (lazy local dial) because adbd
 * kills idle unauthenticated conns - the local dial must happen at splice
 * time, not at park time. Reconnects forever; runs detached (setsid).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <time.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/types.h>

static int dial(const char *host, const char *port) {
    /* Numeric IP only - getaddrinfo() under untrusted_app goes through
     * netd's dnsproxyd socket which can hang; literal IPs need no lookup. */
    int p = atoi(port);
    if (p <= 0 || p > 65535) return -1;
    int fd = -1;
    struct sockaddr_in a4;
    memset(&a4, 0, sizeof a4);
    a4.sin_family = AF_INET;
    a4.sin_port = htons(p);
    if (inet_pton(AF_INET, host, &a4.sin_addr) == 1) {
        fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd >= 0) {
            if (connect(fd, (struct sockaddr *)&a4, sizeof a4) != 0) { close(fd); fd = -1; }
        }
        return fd;
    }
    struct sockaddr_in6 a6;
    memset(&a6, 0, sizeof a6);
    a6.sin6_family = AF_INET6;
    a6.sin6_port = htons(p);
    if (inet_pton(AF_INET6, host, &a6.sin6_addr) == 1) {
        fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            if (connect(fd, (struct sockaddr *)&a6, sizeof a6) != 0) { close(fd); fd = -1; }
        }
        return fd;
    }
    return -1;
}

static int write_all(int fd, const char *buf, ssize_t len) {
    for (ssize_t off = 0; off < len;) {
        ssize_t w = write(fd, buf + off, len - off);
        if (w <= 0) return -1;
        off += w;
    }
    return 0;
}

static void logf_(const char *fmt, ...) {
    char b[256];
    int off = snprintf(b, sizeof b, "[%ld] ", (long)time(NULL));
    va_list ap; va_start(ap, fmt);
    vsnprintf(b + off, sizeof b - off, fmt, ap);
    va_end(ap);
    fprintf(stderr, "%s\n", b);
    fflush(stderr);
    int fd = open("/data/local/tmp/adbstitch.log", O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    write(fd, b, strlen(b)); write(fd, "\n", 1);
    close(fd);
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s remote_ip remote_port local_port\n", argv[0]);
        return 64;
    }
    const char *rip = argv[1], *rport = argv[2], *lport = argv[3];
    char buf[65536];

    for (;;) {
        int r = dial(rip, rport);          /* park: outbound conn to bridge */
        if (r < 0) { logf_("remote dial fail e=%d", errno); sleep(5); continue; }
        int one = 1, ka = 30, ki = 10, kc = 3;
        setsockopt(r, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
        setsockopt(r, IPPROTO_TCP, TCP_KEEPIDLE, &ka, sizeof(ka));
        setsockopt(r, IPPROTO_TCP, TCP_KEEPINTVL, &ki, sizeof(ki));
        setsockopt(r, IPPROTO_TCP, TCP_KEEPCNT, &kc, sizeof(kc));
        logf_("parked");

        /* Park phase: wait for the first byte (splice begins). A dead
         * network can leave a zombie conn without FIN - poll timeout
         * forces a re-park every 90s so it self-heals. */
        struct pollfd pr = { .fd = r, .events = POLLIN };
        ssize_t got = -1;
        for (;;) {
            int n = poll(&pr, 1, 90000);
            if (n == 0) { logf_("park idle-timeout"); got = -2; break; }
            if (n < 0) { if (errno == EINTR) continue; break; }
            if (pr.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
            if (!(pr.revents & POLLIN)) continue;
            got = read(r, buf, sizeof buf);
            break;  /* got<=0 => remote EOF, got>0 => splice starts */
        }
        if (got <= 0) { logf_("park end got=%d e=%d", (int)got, errno); close(r); sleep(3); continue; }
        logf_("splice first=%d", (int)got);

        /* Fresh local conn at handshake time (adbd kills idle ones). */
        int l = dial("127.0.0.1", lport);
        if (l < 0) { logf_("local dial fail e=%d", errno); close(r); sleep(3); continue; }
        if (write_all(l, buf, got) < 0) { logf_("local write fail e=%d", errno); close(r); close(l); sleep(3); continue; }
        logf_("bridged");

        /* Bridge phase: two-way pump. */
        struct pollfd p[2] = {{.fd = r, .events = POLLIN}, {.fd = l, .events = POLLIN}};
        for (;;) {
            int n = poll(p, 2, -1);
            if (n < 0) { if (errno == EINTR) continue; break; }
            int dead = 0;
            for (int i = 0; i < 2; i++) {
                if (p[i].revents & (POLLERR | POLLHUP | POLLNVAL)) { logf_("fd%d revents=%x", i, p[i].revents); dead = 1; break; }
                if (!(p[i].revents & POLLIN)) continue;
                ssize_t n2 = read(p[i].fd, buf, sizeof buf);
                if (n2 <= 0 || write_all(p[1 - i].fd, buf, n2) < 0) { logf_("fd%d rw n=%d e=%d", i, (int)n2, errno); dead = 1; break; }
            }
            if (dead) break;
        }
        logf_("pump end e=%d", errno);
        close(r);
        close(l);
        sleep(3);
    }
}
