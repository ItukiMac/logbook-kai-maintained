# P2: probability calculator model extraction

This is an independent, small first extraction, not completion of the P1 shared service/event API or general plugin modularization.

- `ProbabilityCalculator` has no JavaFX, plugin, network, config or storage dependency.
- The existing FXML, `ProbCalcController.ProbRow` public constructor/getters, row formatting, limit/step behavior and 50/75/90/95% milestone rows remain.
- Numerical formulas are deliberately preserved. Very small probability accuracy improvements and excessive row-count input limits require separate work.
- Safety differences: non-finite input produces no rows; a long iteration counter avoids an integer-wrap infinite loop for large step/limit values.
- Seven JUnit tests include 180 legacy-equivalence input combinations, sorted/unique rows, milestones beyond limit, invalid inputs, overflow and immutable output.

## Gates

Run Java21/25 builds and existing KLB/KQS/distribution CI. Mint is NOT needed for the model tests. Final GUI entry/formatting, window settings and application integration remain a later combined Mint validation item. No release, install, auto.sh change or user-data migration is part of this change.
