package logbook.internal.kancolle;

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
