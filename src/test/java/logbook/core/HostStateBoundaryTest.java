package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class HostStateBoundaryTest {
    @Test void localCommandsAndApiWritersUseOneBoundaryAndOrderedEvents() throws Exception {
        var service=new GameStateService();var active=new AtomicInteger();var events=new CopyOnWriteArrayList<Long>();
        try(var subscription=service.subscribe(s->events.add(s.sequence()));var pool=Executors.newFixedThreadPool(3)) {
            var futures=new ArrayList<Future<?>>();
            futures.add(pool.submit(()->{for(int i=0;i<50;i++)service.applyApiUpdate("fixture",()->{assertEquals(1,active.incrementAndGet());Thread.yield();active.decrementAndGet();return true;});}));
            futures.add(pool.submit(()->{for(int i=0;i<50;i++)service.applyLocalUpdate("fixture",()->{assertEquals(1,active.incrementAndGet());Thread.yield();active.decrementAndGet();return true;},Boolean::booleanValue);}));
            futures.add(pool.submit(()->{for(int i=0;i<50;i++)service.read(()->{assertEquals(0,active.get());return null;});}));
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertEquals(100,events.size());for(int i=0;i<100;i++)assertEquals(i+1L,events.get(i));
    }
    @Test void localNoopDoesNotInventAnUpdateOrNotify() throws Exception {
        var service=new GameStateService();var count=new AtomicInteger();var before=service.snapshot();
        try(var subscription=service.subscribe(s->count.incrementAndGet())) {
            assertEquals("unchanged",service.applyLocalUpdate("fixture",()->"unchanged",ignored->false));
        }
        assertSame(before,service.snapshot());assertEquals(0,count.get());
    }
    @Test void failuresPublishPartialStateAndReleaseTheBoundary() {
        var service=new GameStateService();var failure=new IllegalStateException("synthetic");
        assertSame(failure,assertThrows(IllegalStateException.class,()->service.applyLocalUpdate("fixture",()->{throw failure;},ignored->true)));
        assertFalse(service.snapshot().updateSucceeded());assertEquals("host/local/fixture",service.snapshot().sourceApi());
        service.applyApiUpdate("fixture",()->true);assertTrue(service.snapshot().updateSucceeded());assertEquals(2,service.snapshot().sequence());
    }
    @Test void apiExceptionAlsoProducesAFailureReceipt() {
        var service=new GameStateService();
        assertThrows(IllegalArgumentException.class,()->service.applyApiUpdate("/kcsapi/api_get_member/questlist",()->{throw new IllegalArgumentException("fixture");}));
        assertFalse(service.latestQuestReceipt().updateSucceeded());assertSame(service.snapshot(),service.latestQuestReceipt());
    }
    @Test void nestedWritesAreRejectedForBothDirections() {
        var service=new GameStateService();
        service.applyApiUpdate("fixture",()->{assertThrows(IllegalStateException.class,()->service.applyLocalUpdate("nested",()->true,Boolean::booleanValue));return true;});
        service.applyLocalUpdate("fixture",()->{assertThrows(IllegalStateException.class,()->service.applyApiUpdate("nested",()->true));return true;},Boolean::booleanValue);
        assertEquals(2,service.snapshot().sequence());
    }
    @Test void localOperationsCannotMasqueradeAsQuestReceipts() {
        var service=new GameStateService();var ran=new AtomicBoolean();
        assertThrows(IllegalArgumentException.class,()->service.applyLocalUpdate("api_get_member/questlist",()->{ran.set(true);return true;},Boolean::booleanValue));
        assertFalse(ran.get());assertNull(service.latestQuestReceipt());
    }
    @Test void unrelatedTrafficAndLocalCommandsRetainExactQuestEvidence() {
        var service=new GameStateService();service.applyApiUpdate("/kcsapi/api_get_member/questlist",()->true);var receipt=service.latestQuestReceipt();
        service.applyApiUpdate("/kcsapi/api_port/port",()->true);service.applyLocalUpdate("ship-labels",()->true,Boolean::booleanValue);
        assertSame(receipt,service.latestQuestReceipt());assertNotSame(receipt,service.snapshot());
        assertEquals(1,receipt.sequence());assertEquals(3,service.snapshot().sequence());
        service.applyApiUpdate("/kcsapi/api_get_member/questlist",()->false);assertFalse(service.latestQuestReceipt().updateSucceeded());assertEquals(4,service.latestQuestReceipt().sequence());
    }
    @Test void legacyProvidersRemainSourceAndBinaryCompatible() {
        var service=new GameStateService();logbook.plugin.api.GameStateView legacy=new logbook.plugin.api.GameStateView(){
            public logbook.plugin.api.GameStateSnapshot snapshot(){return service.snapshot();}
            public AutoCloseable subscribe(java.util.function.Consumer<logbook.plugin.api.GameStateSnapshot> c){return service.subscribe(c);}
        };
        assertNull(legacy.latestQuestReceipt());service.applyApiUpdate("/kcsapi/api_get_member/questlist",()->true);assertSame(service.snapshot(),legacy.latestQuestReceipt());
    }
    @Test void eachLiveProjectionWaitsForHostWriterInsteadOfReadingHalfUpdatedCollections() throws Exception {
        var service=GameStateService.shared();
        List<Callable<?>> queries=List.of(ShipRosterService::snapshot,ShipStatisticsService::snapshot,
                AirBaseStatusService::snapshot,EquipmentDataService::inventory,EquipmentDataService::aircraft,
                EquipmentDataService::categories,()->EquipmentDataService.itemName(1),QuestListReadService::snapshot);
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(var query:queries) {
                var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                var writer=pool.submit(()->service.applyLocalUpdate("synthetic-boundary",()->{
                    entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return false;
                },Boolean::booleanValue));
                Future<?> reader=null;
                try {
                    assertTrue(entered.await(10,TimeUnit.SECONDS));
                    var began=new CountDownLatch(1);reader=pool.submit(()->{began.countDown();return query.call();});
                    assertTrue(began.await(10,TimeUnit.SECONDS));final Future<?> held=reader;
                    assertThrows(TimeoutException.class,()->held.get(100,TimeUnit.MILLISECONDS));
                }finally{release.countDown();}
                writer.get(10,TimeUnit.SECONDS);assertNotNull(reader);reader.get(10,TimeUnit.SECONDS);
            }
        }
    }
}
