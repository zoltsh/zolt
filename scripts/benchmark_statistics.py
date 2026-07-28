"""Shared statistical outcome rules for Zolt competitor benchmarks."""

from __future__ import annotations

import hashlib
import math
import random
import statistics
from typing import Iterable


MINIMUM_SAMPLES = 5
MINIMUM_EFFECT_RATIO = 1.05
CONFIDENCE_LEVEL = 0.95
BOOTSTRAP_ITERATIONS = 10_000

OUTCOME_RULE: dict[str, object] = {
    "id": "paired-bootstrap-median-ratio-v1",
    "perspective": "first named tool",
    "minimumSamples": MINIMUM_SAMPLES,
    "minimumEffectRatio": MINIMUM_EFFECT_RATIO,
    "confidenceLevel": CONFIDENCE_LEVEL,
    "bootstrapIterations": BOOTSTRAP_ITERATIONS,
    "decision": (
        "faster when the paired median-duration-ratio confidence interval is "
        f"entirely below {1 / MINIMUM_EFFECT_RATIO:.4f}; slower when it is "
        f"entirely above {MINIMUM_EFFECT_RATIO:.4f}; otherwise inconclusive"
    ),
}


def _seed(value: str) -> int:
    digest = hashlib.sha256(value.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


def _percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = probability * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + ((ordered[upper] - ordered[lower]) * fraction)


def _paired_ratios(
    first_values: Iterable[int | float],
    second_values: Iterable[int | float],
) -> list[float]:
    return [
        float(first) / float(second)
        for first, second in zip(first_values, second_values)
        if isinstance(first, (int, float))
        and isinstance(second, (int, float))
        and first > 0
        and second > 0
    ]


def compare_paired_samples(
    first_tool: str,
    first_values: list[int | float],
    second_tool: str,
    second_values: list[int | float],
    *,
    seed: str,
) -> dict[str, object]:
    """Classify the first tool against the second using the declared rule."""

    ratios = _paired_ratios(first_values, second_values)
    result: dict[str, object] = {
        "firstTool": first_tool,
        "secondTool": second_tool,
        "outcome": "inconclusive",
        "pairedSamples": len(ratios),
        "medianDurationRatio": (
            round(statistics.median(ratios), 4) if ratios else None
        ),
        "medianDurationRatio95ConfidenceInterval": None,
        "minimumEffectRatio": MINIMUM_EFFECT_RATIO,
        "reason": "insufficient-paired-samples",
    }
    if len(ratios) < MINIMUM_SAMPLES:
        return result

    generator = random.Random(_seed(seed))
    bootstrapped_medians = [
        statistics.median(generator.choices(ratios, k=len(ratios)))
        for _ in range(BOOTSTRAP_ITERATIONS)
    ]
    tail = (1 - CONFIDENCE_LEVEL) / 2
    lower = _percentile(bootstrapped_medians, tail)
    upper = _percentile(bootstrapped_medians, 1 - tail)
    interval = [round(lower, 4), round(upper, 4)]
    result["medianDurationRatio95ConfidenceInterval"] = interval

    faster_boundary = 1 / MINIMUM_EFFECT_RATIO
    if upper < faster_boundary:
        result["outcome"] = "faster"
        result["reason"] = "effect-and-noise-threshold-passed"
    elif lower > MINIMUM_EFFECT_RATIO:
        result["outcome"] = "slower"
        result["reason"] = "effect-and-noise-threshold-passed"
    else:
        result["reason"] = "effect-or-noise-threshold-not-passed"
    return result


def _reverse_comparison(comparison: dict[str, object]) -> dict[str, object]:
    interval = comparison["medianDurationRatio95ConfidenceInterval"]
    reversed_interval = None
    if isinstance(interval, list) and len(interval) == 2:
        lower, upper = interval
        if isinstance(lower, (int, float)) and isinstance(upper, (int, float)):
            reversed_interval = [round(1 / upper, 4), round(1 / lower, 4)]
    ratio = comparison["medianDurationRatio"]
    reversed_ratio = (
        round(1 / ratio, 4)
        if isinstance(ratio, (int, float)) and ratio > 0
        else None
    )
    reverse_outcome = {
        "faster": "slower",
        "slower": "faster",
        "inconclusive": "inconclusive",
    }[str(comparison["outcome"])]
    return {
        **comparison,
        "firstTool": comparison["secondTool"],
        "secondTool": comparison["firstTool"],
        "outcome": reverse_outcome,
        "medianDurationRatio": reversed_ratio,
        "medianDurationRatio95ConfidenceInterval": reversed_interval,
    }


def classify_workflow(
    samples_by_tool: dict[str, list[int | float]],
    *,
    baseline_tool: str = "zolt",
    seed: str,
) -> dict[str, object]:
    """Return pairwise outcomes, a conclusive winner, and Zolt's headline result."""

    tools = [tool for tool, values in samples_by_tool.items() if values]
    comparisons: dict[tuple[str, str], dict[str, object]] = {}
    for first_index, first_tool in enumerate(tools):
        for second_tool in tools[first_index + 1 :]:
            comparison = compare_paired_samples(
                first_tool,
                samples_by_tool[first_tool],
                second_tool,
                samples_by_tool[second_tool],
                seed=f"{seed}:{first_tool}:{second_tool}",
            )
            comparisons[(first_tool, second_tool)] = comparison
            comparisons[(second_tool, first_tool)] = _reverse_comparison(comparison)

    winner = (
        next(
            (
                tool
                for tool in tools
                if all(
                    comparisons[(tool, competitor)]["outcome"] == "faster"
                    for competitor in tools
                    if competitor != tool
                )
            ),
            None,
        )
        if len(tools) > 1
        else None
    )
    medians = {
        tool: statistics.median(values)
        for tool, values in samples_by_tool.items()
        if values
    }
    competitors = {
        tool: median
        for tool, median in medians.items()
        if tool != baseline_tool
    }
    comparisons_vs_baseline = {
        tool: comparisons[(baseline_tool, tool)]
        for tool in tools
        if tool != baseline_tool and (baseline_tool, tool) in comparisons
    }
    slower_references = [
        tool
        for tool, comparison in comparisons_vs_baseline.items()
        if comparison["outcome"] == "slower"
    ]
    reference_tool = min(competitors, key=competitors.get) if competitors else None
    if slower_references:
        # A noisy fastest-median comparator must not hide a conclusive loss to
        # another tool. Among conclusive losses, report the lowest-median tool.
        reference_tool = min(slower_references, key=medians.get)
        baseline_outcome = "slower"
    elif comparisons_vs_baseline and all(
        comparison["outcome"] == "faster"
        for comparison in comparisons_vs_baseline.values()
    ):
        # Zolt gets an overall faster result only when it clears the rule
        # against every enabled comparator.
        baseline_outcome = "faster"
    else:
        baseline_outcome = "inconclusive"
    return {
        "outcomeRule": OUTCOME_RULE,
        "winner": winner,
        "fastestMedianTool": min(medians, key=medians.get) if medians else None,
        "zoltOutcome": baseline_outcome,
        "zoltReferenceTool": reference_tool,
        "comparisonsVsZolt": comparisons_vs_baseline,
    }
