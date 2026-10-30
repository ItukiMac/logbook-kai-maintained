# D5 single-change delivery and M2 bounded source recipe

The actual published baseline is data-v2026.10.25.1, source dcabdeafde8aed5f17ee8a00bbf5d94c50ecff8a,
archive SHA2560673bc4ee9de40f67afbefd8cf17d079860cddb11fe7826da03db3f84466fd70.
The A1 correction already committed in source is flagship20->5 plus fleet level10,
with the source comparison recorded in r3-scalar-changes/source-field-review.
On2026-10-30 the wiki rendered source and the live Zekamashi table again show5/10.
These are two community sources, not an official game specification or whole-rule certification.

The single-definition trial tool verifies the pinned actual public baseline and
all152 entries, reads A1 from the exact Git commit, and accepts only that reviewed
semantic delta. All151 other definition payloads stay byte-identical. It emits a
NEW archive and provenance receipt. The dedicated CI distributes baseline/trial and
actual built application JAR through an artifact, then separate Java21/25 runners
verify the received hashes, apply the actual generations, check exactly one changed
definition and restore the retained original. The actual JAR and both archives remain
unchanged. This is development artifact delivery, not a newly published Release or
production application update.

`routine_scalar_candidate.py` is a bounded recipe for these two A1 scalar fields.
It parses fresh captured HTML from the exact wiki and Zekamashi URLs, verifies the
retrieved bytes/hashes and24-hour clock, requires unique rows and exact agreement,
then constructs a hash-bound isolated candidate through existing budget/approval
and full managed-source gates. Only those numeric predicates can change; all other
conditions and unknown fields remain. The recipe assertion is explicitly local and
delegated, never represented as a fresh human approval or publisher authentication.

Ten deterministic tests cover agreement, conflict, stale/future/failed retrieval,
corruption, duplicate sources/rows, changed layouts, exact delta and unchanged bytes.
The real Zekamashi HTML was retrieved and parsed as5/10. The direct wiki retrieval
returned HTTP403. A rendered browser/search read is not a successful unattended
HTTP receipt. Therefore the actual unattended M2 source-to-publication cycle is NOT
complete. No schedule, auto-approval of other rules, automatic merge or publication
is enabled. General semantic inference and other recipes remain outside this narrow
lane. An accessible permitted source feed and a separately validated publication
policy are still required before declaring M2 complete.
