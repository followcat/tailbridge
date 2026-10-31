#!/usr/bin/env python3
"""adb-over-tailnet bridge, v4.

Phone (behind FlClash/mihomo tsnet, outbound-only) dials out and parks a conn
on PARK_PORT. Desktop `adb connect <fvps>:ADB_PORT` splices onto it; phone side
(adbstitch) forwards to 127.0.0.1:<adbd>. One adb session at a time.

v2 fix: park() kept its StreamReader.read() pending after handoff, so the
splice pump's read() raised "already waiting" RuntimeError. Now the splice
cancels the park task and awaits it before pumping.

v3 fix: a splice only ended when a socket closed, so an adb server holding a
dead connection (failed handshake, device marked "offline", socket left open)
kept the single park slot forever and every later `adb connect` got refused
with "no phone parked". A watchdog now closes a splice after SPLICE_IDLE_S
without any phone->desktop byte (handshake should complete fast) and after
SPLICE_ESTABLISHED_S of silence once traffic has flowed.

v4 fix: adb servers retry dead transports in a fast loop (~100 conn/s seen).
Each retry used to consume a fresh park instantly, die mid-handshake, and kill
the park with it -- the phone's re-park then met the next stale retry, forever.
Now an adb conn must produce a valid `CNXN` header within ADB_HANDSHAKE_S
*before* it may consume a park; stale retries die alone and the park survives.
Also: an adb conn arriving with no park waits PARK_WAIT_S for one instead of
being refused instantly (cuts the reconnect storm), and park sockets get TCP
keepalive so a silently-dead park vacates the slot within ~105s.
"""
import asyncio
import socket
import time

TS_IP = "100.126.239.27"
PARK_PORT = 2222
ADB_PORT = 2223
SPLICE_IDLE_S = 30
SPLICE_ESTABLISHED_S = 600
ADB_HANDSHAKE_S = 3   # adb conn must send CNXN within this or die alone
PARK_WAIT_S = 3       # adb conn with no phone waits this long for a park

parked = None   # [reader, writer, bytearray, task]
park_ev = asyncio.Event()
_last_nopark_log = 0.0


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


def keepalive(writer):
    sock = writer.get_extra_info("socket")
    if sock is None:
        return
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 60)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 15)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
    except OSError:
        pass


async def park(reader, writer):
    global parked
    peer = writer.get_extra_info("peername")
    if parked:
        log("park: replacing old parked conn")
        try:
            parked[1].close()
        except Exception:
            pass
    keepalive(writer)
    slot = [reader, writer, bytearray(), asyncio.current_task()]
    parked = slot
    park_ev.set()
    log("parked phone conn from", peer)
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            slot[2] += data
    except asyncio.CancelledError:
        pass  # consumed by a splice - reader handed off
    except Exception as e:
        log("park read err:", repr(e))
    if parked and parked[1] is writer:
        parked = None
        log("parked conn lost from", peer)


async def adb(reader, writer):
    global parked, _last_nopark_log
    peer = writer.get_extra_info("peername")
    if not parked:
        try:
            await asyncio.wait_for(park_ev.wait(), PARK_WAIT_S)
        except asyncio.TimeoutError:
            pass
    if not parked:
        now = time.monotonic()
        if now - _last_nopark_log > 5:
            _last_nopark_log = now
            log("adb conn from", peer, "- no phone parked, closing")
        writer.close()
        return
    # CNXN gate: prove this is a live adb client before it may take the park.
    try:
        head = await asyncio.wait_for(reader.read(64), ADB_HANDSHAKE_S)
    except (asyncio.TimeoutError, Exception):
        head = b""
    if not head.startswith(b"CNXN"):
        log("adb conn from", peer, "- no valid CNXN, closing (park kept)")
        writer.close()
        return
    if not parked:
        # someone else took the park while we were reading the handshake
        writer.close()
        return
    pr, pw, before, ptask = parked
    parked = None
    park_ev.clear()
    if ptask and not ptask.done():
        ptask.cancel()
        try:
            await ptask
        except BaseException:
            pass  # cancellation unwind; reader now released
    log("splicing adb", peer, "<-> parked phone")
    if before:
        writer.write(bytes(before))
        await writer.drain()
    pw.write(head)
    await pw.drain()

    state = {"last": time.monotonic(), "up": False}

    async def pump(r, w, tag, phone_side=False):
        try:
            while True:
                data = await r.read(65536)
                if not data:
                    break
                state["last"] = time.monotonic()
                if phone_side:
                    state["up"] = True
                log(tag, len(data), "B")
                w.write(data)
                await w.drain()
        except Exception as e:
            log(tag, "err", repr(e))
        finally:
            try:
                w.close()
            except Exception:
                pass

    async def watchdog():
        while True:
            await asyncio.sleep(5)
            idle = time.monotonic() - state["last"]
            if idle > (SPLICE_ESTABLISHED_S if state["up"] else SPLICE_IDLE_S):
                log(f"splice idle {idle:.0f}s for", peer, "- closing")
                for w in (writer, pw):
                    try:
                        w.close()
                    except Exception:
                        pass
                return

    tasks = [
        asyncio.create_task(pump(reader, pw, "pc->ph")),
        asyncio.create_task(pump(pr, writer, "ph->pc", phone_side=True)),
        asyncio.create_task(watchdog()),
    ]
    _, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    for t in pending:
        t.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    log("splice done for", peer)


async def main():
    s1 = await asyncio.start_server(park, TS_IP, PARK_PORT)
    s2 = await asyncio.start_server(adb, TS_IP, ADB_PORT)
    log(f"bridge up park={PARK_PORT} adb={ADB_PORT}")
    async with s1, s2:
        await asyncio.gather(s1.serve_forever(), s2.serve_forever())


asyncio.run(main())
