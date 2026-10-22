"""Exponential backoff with equal jitter. Pure; the caller owns the clock."""
import random
from typing import Callable


class Backoff:
    def __init__(self, base: float, cap: float, rng: Callable[[], float] = random.random):
        self.base = base
        self.cap = cap
        self.rng = rng
        self.failures = 0

    def next_delay(self, floor: float = 0.0) -> float:
        """Delay before the next attempt; grows with each call until reset()."""
        raw = min(self.cap, self.base * (2 ** min(self.failures, 20)))
        self.failures += 1
        # equal jitter: half fixed, half random, so peers never retry in lockstep
        return max(floor, raw * (0.5 + 0.5 * self.rng()))

    def reset(self) -> None:
        self.failures = 0
