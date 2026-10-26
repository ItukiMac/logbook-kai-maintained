# R2 review corrections

The initial PR39 review identified two defects in source86e3764. Both were reproduced and corrected in c741a81c5816a8f7b473d58284d436ea55207416.

1. Equipment-category aggregate state: restored children are selected before listeners exist. RestoredCheckBoxGroup now sets the parent selected/indeterminate state BEFORE installing the existing bidirectional listeners. It does not change child choices or persist configuration. The actual ItemItemController category creation calls this helper. A JavaFX regression covers none/partial/all restored selections, preserved children, subsequent child aggregation and parent-to-child changes.
2. Maintenance resume metadata: checkpoint reuse now also requires an identical estimate_digest. Correcting missing/invalid bounds invalidates the earlier error; raising estimated costs rechecks the current budget. Approval identity stays bound to content/source evidence. Three regressions cover missing bounds repaired, invalid bounds repaired and changed estimates allowing later affordable records to advance.

Hash-checked worker40272687307 re-ran the complete isolated suite before updating the dedicated source branch: Java21/Xvfb/real pack183 discovered,182 successful,0 failures/errors,1 deliberate public-network skip; Python37 scripts+12 tools=49 successful. These counts replace the earlier182/46 baseline for the corrected source. Final Java25/cross-plugin PR runs and external re-review are recorded on PR39; this file does not predeclare their results.

KQS PR4 was merged as3148ed96aeddce3f18fe26ebac4234871df09678 after new/legacy-host CI and clean review. Its source pin319f8bee9eb5566b9fe5a026f55dc0df28594099 is unchanged by these two host corrections. No release or production installation was performed. All native validation, source-semantic comparison, full physical packaging and provider-executing automation limitations in current-status.md remain in force.
