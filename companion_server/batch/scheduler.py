from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Awaitable, Callable, TypeVar

T = TypeVar("T")


@dataclass
class ScheduledResult:
    executed: bool
    cancelled: bool


class GpuScheduler:
    def __init__(self, max_concurrency: int = 1) -> None:
        self._semaphore = asyncio.Semaphore(max(1, max_concurrency))

    async def run(
        self,
        work: Callable[[], Awaitable[T]],
        is_disconnected: Callable[[], Awaitable[bool]],
        poll_interval: float = 0.01,
    ) -> T:
        while self._semaphore.locked():
            if await is_disconnected():
                raise asyncio.CancelledError("client disconnected before GPU execution")
            await asyncio.sleep(poll_interval)

        async with self._semaphore:
            if await is_disconnected():
                raise asyncio.CancelledError("client disconnected before GPU execution")
            return await work()
