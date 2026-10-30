package local.kancolle.kqs;
import java.util.Map;
public final class QuestStoreTest {
    private static void assertTrue(boolean v,String name){if(!v)throw new AssertionError(name);}
    private static void assertEqual(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
    public static void main(String[] args) {
        QuestStore store=new QuestStore();
        String list="svdata={\"api_result\":1,\"api_data\":{\"api_exec_count\":1,\"api_list\":[{\"api_no\":101,\"api_title\":\"\\u8a66\\u9a13<br>\\u4efb\\u52d9\",\"api_state\":2,\"api_detail\":\"\\u51fa\\u6483\",\"api_progress_flag\":1},-1]}}";
        assertTrue(store.accept("/kcsapi/api_get_member/questlist",Map.of("api_page_no","1","api_tab_id","1"),list),"questlist");
        assertEqual(store.activeCount(),1);
        assertTrue(store.prompt(false).contains("試験 任務"),"unicode/html");
        assertTrue(store.prompt(false).contains("50%以上"),"progress");
        assertTrue(!store.prompt(false).contains("api_token"),"private-data");
        String ok="svdata={\"api_result\":1,\"api_data\":{}}";
        assertTrue(store.accept("/kcsapi/api_req_quest/stop",Map.of("api_quest_id","101"),ok),"stop");
        assertEqual(store.activeCount(),0);
        assertTrue(store.accept("/kcsapi/api_req_quest/start",Map.of("api_quest_id","101"),ok),"start");
        assertEqual(store.activeCount(),1);
        assertTrue(store.accept("/kcsapi/api_req_quest/clearitemget",Map.of("api_quest_id","101"),ok),"clear");
        assertEqual(store.activeCount(),0);
        assertTrue(!store.accept("/kcsapi/api_req_quest/start",Map.of("api_quest_id","101"),"svdata={\"api_result\":0}"),"api error");
        assertTrue(!store.accept("/kcsapi/api_get_member/basic",Map.of(),ok),"not whitelisted");
        assertTrue(!store.accept("/kcsapi/api_get_member/questlist",Map.of(),"bad-json"),"malformed");
        assertEqual(store.activeCount(),0);
        System.out.println("QuestStore tests PASS");
    }
}
