# Results

Raw benchmark output. Every file carries a header with the commit, the JDK
build, the CPU and the exact command that produced it, so a file can always be
traced back to a state of the repository. The `.json` twins hold the per-fork
raw data, which sometimes matters more than the summary: see the bimodality at
size 1024 in `docs/final-report.md`.

## Which numbers to use

**`session/`** is the citable one. It holds the controlled session produced by
`scripts/session.sh`: all five variants plus the bandwidth control, measured
under a single machine state, with a manifest recording that state as it
actually was. If you want a number for the article, take it from here.

**`experiments/`** holds controlled experiments that vary one property to
answer one question, produced by the `scripts/experiment-*.sh` scripts. Their
numbers are valid for the comparison they were designed for and are not
interchangeable with the session figures.

**`archive/`** holds the milestone runs M1 to M4, which document how the
investigation proceeded: the auto-vectorization of the array baseline, the
alignment regression and the hypotheses discarded along the way. **They are
superseded.** Do not quote them for the article: the machine state, the
benchmark state isolation and the segment alignment all changed afterwards.
Their reports live in `docs/` and carry the same warning.

## Reading a run

The mean is not always the statistic you want. JMH reports a fork-to-fork
error bar, but a bimodal distribution can hide behind a plausible-looking
mean, and one of the runs here has exactly that shape:

```bash
jq '.[] | select(.params.size=="1024") | [.benchmark, (.primaryMetric.rawData[] | add/length)]' \
  experiments/2026-08-26-alignment-by-size-avgt.json
```

Numbers in `-perfasm-` and `-perfnorm-` files are not citable as timings: the
profiler perturbs the run, and in at least one case it also shifts which mode
a variant lands in.
