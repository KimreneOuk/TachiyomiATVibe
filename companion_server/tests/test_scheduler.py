from __future__ import annotations

import asyncio

import pytest

from batch.scheduler import GpuScheduler


@pytest.mark.anyio
async def test_scheduler_drops_task_when_client_disconnects_before_execution() -> None:
    scheduler = GpuScheduler(max_concurrency=1)
    started = asyncio.Event()
    release = asyncio.Event()
    executed = False

    async def long_work() -> str:
        started.set()
        await release.wait()
        return "done"

    async def queued_work() -> str:
        nonlocal executed
        executed = True
        return "should not run"

    async def connected() -> bool:
        return False

    disconnect = False

    async def disconnected() -> bool:
        return disconnect

    first = asyncio.create_task(scheduler.run(long_work, connected))
    await started.wait()
    second = asyncio.create_task(scheduler.run(queued_work, disconnected, poll_interval=0.001))
    await asyncio.sleep(0.01)
    disconnect = True

    with pytest.raises(asyncio.CancelledError):
        await second
    release.set()
    assert await first == "done"
    assert executed is False
