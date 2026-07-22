/**
 * useLagHistory — client-side lag sample accumulation (023, F6).
 *
 * There is no server-side time series in MVP (design D6): the Lag history
 * sparkline accumulates one sample per poll from page open. Cold start renders
 * a flat line from the first sample.
 */

import { useEffect, useRef, useState } from 'react';

const MAX_SAMPLES = 120;

/**
 * Append one lag sample whenever the sync-state query delivers fresh data.
 *
 * @param lag           current lag value (null while loading / empty state)
 * @param dataUpdatedAt TanStack Query's dataUpdatedAt — a new value marks a completed poll
 * @returns samples accumulated since page open (bounded)
 */
export function useLagHistory(lag: number | null, dataUpdatedAt: number): number[] {
  const [samples, setSamples] = useState<number[]>([]);
  const lastUpdateRef = useRef<number>(0);

  useEffect(() => {
    if (lag === null || dataUpdatedAt === 0 || dataUpdatedAt === lastUpdateRef.current) {
      return;
    }
    lastUpdateRef.current = dataUpdatedAt;
    setSamples((prev) => [...prev, lag].slice(-MAX_SAMPLES));
  }, [lag, dataUpdatedAt]);

  return samples;
}

/**
 * Build SVG polyline points for the sparkline. A single sample renders flat
 * (duplicated across the width); the y-range pads by 10% so a constant series
 * does not hug the edge.
 */
export function sparklinePoints(samples: number[], width: number, height: number): string {
  if (samples.length === 0) {
    return '';
  }
  const series = samples.length === 1 ? [samples[0], samples[0]] : samples;
  const max = Math.max(...series, 1);
  const min = Math.min(...series, 0);
  const range = Math.max(max - min, 1);
  const pad = height * 0.1;
  const usable = height - pad * 2;

  return series
    .map((value, index) => {
      const x = (index / (series.length - 1)) * width;
      const y = pad + (1 - (value - min) / range) * usable;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}
