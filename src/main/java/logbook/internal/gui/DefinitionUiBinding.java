package logbook.internal.gui;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.event.EventHandler;
import logbook.core.GameDefinitionRuntime;
import logbook.core.LogBookCoreServices;

/** Window-lifetime subscription; queued work never resurrects a closed view. */
final class DefinitionUiBinding implements AutoCloseable {
    private final Window window;
    private final Runnable refresh;
    private final AtomicBoolean queued=new AtomicBoolean();
    private AutoCloseable subscription;
    private boolean closed;
    private final EventHandler<WindowEvent> shown=e->subscribe();
    private final EventHandler<WindowEvent> hidden=e->unsubscribe();
    DefinitionUiBinding(Window window,Runnable refresh) {
        this.window=window;this.refresh=refresh;
        window.addEventHandler(WindowEvent.WINDOW_SHOWN,shown);
        window.addEventHandler(WindowEvent.WINDOW_HIDDEN,hidden);
        if(window.isShowing())subscribe();
    }
    private void subscribe(){
        if(closed || subscription!=null || !GameDefinitionRuntime.active()) return;
        subscription=LogBookCoreServices.gameDataView().subscribe(e->schedule());
        schedule(); // Also refresh a view reopened after a hidden-period publication.
    }
    private void schedule(){
        if(!queued.compareAndSet(false,true))return;
        Platform.runLater(()->{
            queued.set(false);
            if(!closed && window.isShowing()) try(var scope=LogBookCoreServices.pinDefinitions()){refresh.run();}
        });
    }
    private void unsubscribe(){
        if(subscription==null)return;
        try{subscription.close();}catch(Exception e){throw new IllegalStateException(e);}finally{subscription=null;}
    }
    @Override public void close(){
        if(closed)return;closed=true;unsubscribe();
        window.removeEventHandler(WindowEvent.WINDOW_SHOWN,shown);window.removeEventHandler(WindowEvent.WINDOW_HIDDEN,hidden);
    }
}
