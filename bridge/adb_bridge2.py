#!/usr/bin/env python3
"""adb-over-tailnet bridge, v2.

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
"""
import asyncio
import time

TS_IP = "100.126.239.27"
PARK_PORT = 2222
ADB_PORT = 2223
SPLICE_IDLE_S = 30
SPLICE_ESTABLISHED_S = 600

parked = None  # [reader, writer, bytearray, task]


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


async def park(reader, writer):
    global parked
    peer = writer.get_extra_info("peername")
    if parked:
        log("park: replacing old parked conn")
        try:
            parked[1].close()
        except Exception:
            pass
    slot = [reader, writer, bytearray(), asyncio.current_task()]
    parked = slot
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
    global parked
    peer = writer.get_extra_info("peername")
    if not parked:
        log("adb conn from", peer, "- no phone parked, closing")
        writer.close()
        return
    pr, pw, before, ptask = parked
    parked = None
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
