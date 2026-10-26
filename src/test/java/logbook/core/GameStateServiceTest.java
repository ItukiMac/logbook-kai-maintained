package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import logbook.bean.*;
import org.junit.jupiter.api.*;

class GameStateServiceTest {
    Map<Integer,Ship> old;
    @BeforeEach void save(){old=ShipCollection.get().getShipMap();ShipCollection.get().setShipMap(new LinkedHashMap<>());}
    @AfterEach void restore(){ShipCollection.get().setShipMap(old);}
    @Test void snapshotsDeepCopyHostBeansAndCollections() {
        var service=new GameStateService();var ship=new Ship();ship.setId(1);ship.setShipId(3);ship.setLv(10);ship.setSlot(new ArrayList<>(List.of(7)));
        service.applyApiUpdate("/kcsapi/fixture",()->{ShipCollection.get().getShipMap().put(1,ship);return true;});
        var held=service.snapshot();ship.setLv(99);ship.getSlot().set(0,9);
        assertEquals(10,held.ships().getFirst().level());assertEquals(List.of(7),held.ships().getFirst().equipment());
        assertThrows(UnsupportedOperationException.class,()->held.ships().clear());
        assertThrows(UnsupportedOperationException.class,()->held.ships().getFirst().equipment().clear());
    }
    @Test void partialUpdateIsMarkedAndSubscriberFailureDoesNotSuppressOthers() throws Exception {
        var service=new GameStateService();AtomicInteger delivered=new AtomicInteger();
        service.subscribe(s->{throw new IllegalStateException("fixture");});
        var good=service.subscribe(s->{assertSame(s,service.snapshot());delivered.incrementAndGet();});
        service.applyApiUpdate("/kcsapi/fixture",()->false);
        assertFalse(service.snapshot().updateSucceeded());assertEquals(1,delivered.get());good.close();good.close();
        service.applyApiUpdate("/kcsapi/fixture",()->true);assertEquals(1,delivered.get());
    }
    @Test void reentrantMutationDoesNotInterleaveNotifications(){
        var service=new GameStateService();service.subscribe(s->assertThrows(IllegalStateException.class,()->service.applyApiUpdate("nested",()->true)));
        service.applyApiUpdate("/kcsapi/fixture",()->true);assertEquals(1,service.snapshot().sequence());
    }
    @Test void twoConcurrentWritersAndLegacyReadAreSerialized() throws Exception {
        var service=new GameStateService();List<Long> observed=new ArrayList<>();service.subscribe(s->observed.add(s.sequence()));
        AtomicInteger active=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(3)){
            List<Future<?>> futures=new ArrayList<>();
            for(int thread=0;thread<2;thread++)futures.add(pool.submit(()->{for(int n=0;n<100;n++)service.applyApiUpdate("/kcsapi/fixture",()->{assertEquals(1,active.incrementAndGet());Thread.yield();active.decrementAndGet();return true;});}));
            futures.add(pool.submit(()->{for(int n=0;n<100;n++)service.read(()->{assertEquals(0,active.get());return null;});}));
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertEquals(200,observed.size());for(int n=0;n<observed.size();n++)assertEquals(n+1L,observed.get(n));
    }
}
