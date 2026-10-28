from pathlib import Path
p=Path('scripts/java/RuntimeMigrationProbe.java');s=p.read_text()
old='                        if (window.get(2, TimeUnit.SECONDS)) {'
assert s.count(old)==1
s=s.replace(old,'                        if (pollWindow(window, deadline)) {')
marker='    private static void launcher(Path root) throws Exception {'
assert s.count(marker)==1
s=s.replace(marker,'''    /** A timed-out observation is not launcher failure before the existing overall deadline.
     * Cancel queued stale observations without interrupting the FX application thread.
     * Execution/interrupt/cancellation errors are not swallowed or reported as ready.
     */
    static boolean pollWindow(java.util.concurrent.Future<Boolean> window, long deadline)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            window.cancel(false);
            return false;
        }
        try {
            return Boolean.TRUE.equals(window.get(Math.min(TimeUnit.SECONDS.toNanos(2), remaining),
                    TimeUnit.NANOSECONDS));
        } catch (java.util.concurrent.TimeoutException pendingObservation) {
            window.cancel(false);
            return false;
        }
    }

'''+marker)
assert s.count('TimeUnit.SECONDS.toNanos(25)')==1
assert 'if (!ready) throw new AssertionError("Actual Launcher window/local proxy did not become ready")' in s
assert 'new Socket("127.0.0.1", AppConfig.get().getListenPort())' in s
p.write_text(s)
p=Path('scripts/java/RuntimeMigrationReadinessProbe.java');assert not p.exists()
p.write_text(r'''package logbook.internal.kancolle;

import java.util.concurrent.*;

/** Deterministic poll contract checks. Never initializes JavaFX, Config or the actual Launcher. */
public final class RuntimeMigrationReadinessProbe {
    private static int cases;
    private static final class Observation implements Future<Boolean> {
        final Object result; int calls; long nanos; boolean cancelled, interrupt;
        Observation(Object result) { this.result=result; }
        public Boolean get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
            calls++;nanos=unit.toNanos(timeout);
            if(result instanceof TimeoutException e)throw e;
            if(result instanceof ExecutionException e)throw e;
            if(result instanceof InterruptedException e)throw e;
            if(result instanceof CancellationException e)throw e;
            return (Boolean)result;
        }
        public Boolean get(){throw new AssertionError("Unbounded get must not be used");}
        public boolean cancel(boolean mayInterrupt){cancelled=true;interrupt=mayInterrupt;return true;}
        public boolean isCancelled(){return cancelled;}
        public boolean isDone(){return false;}
    }
    private static long future(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(25);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] arguments)throws Exception{
        if(arguments.length!=1||!arguments[0].equals("no-runtime-side-effects"))throw new IllegalArgumentException("Explicit test mode required");
        var ready=new Observation(true);check(RuntimeMigrationProbe.pollWindow(ready,future()),"Completed true observation lost");
        check(!ready.cancelled&&ready.nanos>0&&ready.nanos<=TimeUnit.SECONDS.toNanos(2),"Polling budget changed");cases++;
        var absent=new Observation(false);check(!RuntimeMigrationProbe.pollWindow(absent,future()),"Missing window accepted");cases++;
        var unknown=new Observation(null);check(!RuntimeMigrationProbe.pollWindow(unknown,future()),"Unknown window accepted");cases++;
        var timeout=new Observation(new TimeoutException("pending"));check(!RuntimeMigrationProbe.pollWindow(timeout,future()),"Timeout accepted as ready");
        check(timeout.cancelled&&!timeout.interrupt&&timeout.calls==1,"Stale observation cancellation must not interrupt GUI");cases++;
        var later=new Observation(true);check(RuntimeMigrationProbe.pollWindow(later,future()),"A later true observation cannot recover from a prior pending slice");cases++;
        var expired=new Observation(true);check(!RuntimeMigrationProbe.pollWindow(expired,System.nanoTime()-1),"Expired total budget accepted");
        check(expired.calls==0&&expired.cancelled&&!expired.interrupt,"Expired budget performed another wait");cases++;
        var finalSlice=new Observation(new TimeoutException("last slice"));
        check(!RuntimeMigrationProbe.pollWindow(finalSlice,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500)),"Last slice fabricated readiness");
        check(finalSlice.nanos>0&&finalSlice.nanos<=TimeUnit.MILLISECONDS.toNanos(500),"Last slice extended the original deadline");cases++;
        var failure=new Observation(new ExecutionException(new IllegalStateException("renderer failure")));
        try{RuntimeMigrationProbe.pollWindow(failure,future());throw new AssertionError("Renderer failure swallowed");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,"Cause lost");}cases++;
        var interrupted=new Observation(new InterruptedException("stop"));
        try{RuntimeMigrationProbe.pollWindow(interrupted,future());throw new AssertionError("Interrupt swallowed");}catch(InterruptedException expected){}cases++;
        var cancelled=new Observation(new CancellationException("external cancel"));
        try{RuntimeMigrationProbe.pollWindow(cancelled,future());throw new AssertionError("Cancellation swallowed");}catch(CancellationException expected){}cases++;
        check(cases==10,"Missing cases");
        System.out.println("MIGRATION_READINESS_POLL_OK cases=10 overall_deadline_seconds=25 poll_slice_seconds=2 errors_propagate=true missing_window_rejected=true no_runtime_initialized=true");
    }
}
''',encoding='utf8')
p=Path('scripts/tests/test_runtime_migration_readiness.py');assert not p.exists()
p.write_text(r'''"""Check test-only bounded readiness polling; not a substitute for actual Launcher migration."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
JAR=ROOT/'target/logbook-kai.jar'

@unittest.skipUnless(JAR.is_file() and shutil.which('javac') and shutil.which('java'),
                     'Requires built JAR and Java21+, mandatory within comprehensive verification')
class RuntimeMigrationReadinessTest(unittest.TestCase):
    def test_pending_fx_observation_respects_existing_deadline_and_rejects_real_errors(self):
        root=Path(tempfile.mkdtemp(prefix='migration-readiness-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        digest=hashlib.sha256(JAR.read_bytes()).hexdigest()
        report={'result':'not_completed','production_accessed':False,'launcher_initialized':False,'fixture':str(root)};logs=[]
        try:
            classes=root/'classes';classes.mkdir()
            environment={k:v for k,v in os.environ.items() if k not in ('CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
            result=subprocess.run(['javac','-proc:none','--release','21','-cp',str(JAR),'-d',str(classes),
                str(ROOT/'scripts/java/RuntimeMigrationProbe.java'),str(ROOT/'scripts/java/RuntimeMigrationReadinessProbe.java')],
                capture_output=True,text=True,timeout=60,env=environment)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1])
            result=subprocess.run(['java','-cp',str(JAR)+os.pathsep+str(classes),'logbook.internal.kancolle.RuntimeMigrationReadinessProbe','no-runtime-side-effects'],
                capture_output=True,text=True,timeout=15,cwd=root,env=environment)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1]);self.assertIn('MIGRATION_READINESS_POLL_OK cases=10',result.stdout)
            self.assertFalse((root/'config').exists());self.assertEqual(digest,hashlib.sha256(JAR.read_bytes()).hexdigest())
            report.update(result='passed',cases=10,overall_deadline_seconds=25,poll_slice_seconds=2,
                          errors_propagate=True,missing_window_rejected=True,source_jar_unchanged=True)
        finally:
            (evidence/(root.name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
            (evidence/(root.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
        print('MIGRATION_READINESS_POLL_OK '+json.dumps(report))

if __name__=='__main__':unittest.main()
''',encoding='utf8')
p=Path('scripts/verify_r3.sh');s=p.read_text();old="report={'quest_document_command_tests':document_test_count";assert s.count(old)==1
additional="""# Pending FX observations keep the original deadline; missing GUI/proxy is never success.
readiness_reports=[json.loads(f.read_text()) for f in p.glob('migration-readiness-fixture-*.json')]
assert len(readiness_reports)==1 and readiness_reports[0]['result']=='passed'
readiness=readiness_reports[0]
assert readiness['cases']==10 and readiness['overall_deadline_seconds']==25 and readiness['poll_slice_seconds']==2
assert readiness['errors_propagate'] and readiness['missing_window_rejected'] and readiness['source_jar_unchanged']
assert not readiness['production_accessed'] and not readiness['launcher_initialized']
"""
p.write_text(s.replace(old,additional+"report={'migration_readiness':readiness,'quest_document_command_tests':document_test_count"))
p=Path('docs/maintenance/quest-document-commands-20261029.md');s=p.read_text()
s+='''
## 全体検証で見つかった移行試験の待機処理

最初の候補26241fcの移行専用CIはJava21/25で失敗した。原本保持・外部書込み監視は維持され、
Java21の記録にはRuntimeMigrationProbeのwindow.get(2seconds)のTimeoutExceptionがある。
以前のPR65でも同箇所の失敗が記録されていたため、再実行で通ることだけを恒久対策としない。
テスト専用の観測待機を分離し、2秒の一回分が未完了なら古い観測をcancel(false)して元の25秒期限内で再観測する。
全体25秒・1回2秒の上限、実画面が表示されること、ローカルproxyへ接続できること、移行/原本/復元条件は維持する。
残り時間より長いwaitはしない。実行失敗・割込み・外部取消は伝播し、成功へ変換しない。
新しい決定的な10ケースのprobeとPython1試験を追加する。これは実Launcher移行5件の代替ではない。
この修正はscripts/javaの検証補助だけで、製品ランチャーや移行コードを変更しない。
元の失敗ログ・成果物と新headの検証結果を別々に保持する。
'''
p.write_text(s)
print('MIGRATION_READINESS_FIX_APPLIED total_deadline_25s_unchanged=true true_window_and_proxy_still_required=true no_product_java_changed=true')
