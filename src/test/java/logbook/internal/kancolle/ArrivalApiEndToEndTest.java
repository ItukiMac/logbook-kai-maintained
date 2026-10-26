package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import jakarta.json.Json;
import logbook.api.*;
import logbook.bean.*;
import logbook.internal.listener.APIListener;
import logbook.net.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real JSON decoder + real map handlers + real observer/store; only state services and root are fixtures. */
class ArrivalApiEndToEndTest {
    @TempDir Path temp;
    private static final long NOW=1_700_000_000_000L;
    private void run(boolean hasStart) throws Exception {
        var state=new AppCondition();var decks=new DeckPortCollection();var deck=new DeckPort();
        deck.setShip(List.of(11,-1,-1,-1,-1,-1));decks.getDeckPortMap().put(1,deck);
        var ships=new ShipCollection();var ship=new Ship();ship.setId(11);ship.setShipId(101);ships.getShipMap().put(11,ship);
        var store=new PortArrivalStore(temp);var observer=new PortArrivalRuntime(()->store,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
        try(var states=mockStatic(AppCondition.class);var ports=mockStatic(DeckPortCollection.class);
            var members=mockStatic(ShipCollection.class);var config=mockStatic(AppConfig.class);
            var voice=mockStatic(AppBouyomiConfig.class);var entry=mockStatic(PortArrivalRuntime.class)) {
            states.when(AppCondition::get).thenReturn(state);ports.when(DeckPortCollection::get).thenReturn(decks);
            members.when(ShipCollection::get).thenReturn(ships);config.when(AppConfig::get).thenReturn(mock(AppConfig.class));
            voice.when(AppBouyomiConfig::get).thenReturn(mock(AppBouyomiConfig.class));
            entry.when(()->PortArrivalRuntime.observe(anyString(),any(),any(),anyBoolean())).thenAnswer(call->{
                observer.accept(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));return null;
            });
            var constructor=APIListener.class.getDeclaredConstructor(Stream.class);constructor.setAccessible(true);
            var api=constructor.newInstance(Stream.of(new ApiReqMapStart(),new ApiReqMapNext()));
            if(hasStart)send(api,"start",2,4);
            send(api,"next",0,9);send(api,"next",0,9);
            assertEquals(hasStart?1:0,store.readRange(NOW-1,NOW+1).size());
            if(hasStart){assertTrue(state.isMapStart());assertEquals(1,state.getDeckId());}
        }
    }
    private static void send(APIListener api,String kind,int cell,int event) {
        var req=mock(RequestMetaData.class);when(req.getRequestURI()).thenReturn("/kcsapi/api_req_map/"+kind);
        when(req.getParameter("api_deck_id")).thenReturn("1");
        var data=Json.createObjectBuilder().add("api_result",1).add("api_data",Json.createObjectBuilder()
                .add("api_maparea_id",1).add("api_mapinfo_no",6).add("api_no",cell).add("api_event_id",event)).build();
        var res=mock(ResponseMetaData.class);
        when(res.getResponseBody()).thenReturn(Optional.of(new ByteArrayInputStream(("svdata="+data).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertTrue(api.test(req));api.accept(req,res);
    }
    @Test void realMapHandlersFeedOneRecordedGoalAfterDecodedStart() throws Exception {run(true);}
    @Test void realGoalHandlerWithoutDepartureCannotInventARecord() throws Exception {run(false);}
}
