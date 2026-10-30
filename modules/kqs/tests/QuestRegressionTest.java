package local.kancolle.kqs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import logbook.bean.AppQuest;
import logbook.bean.QuestList;

/** Fixed-time tests for 05:00 JST, freshness, metadata matching and false alerts. */
public final class QuestRegressionTest {
    private static final class MutableClock extends Clock {
        Instant time;
        MutableClock(String iso) { time=Instant.parse(iso); }
        void set(String iso) { time=Instant.parse(iso); }
        void addMinutes(int n) { time=time.plus(Duration.ofMinutes(n)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
    private static void check(boolean ok,String why) {
        if(!ok)throw new AssertionError(why);
    }
    private static AppQuest quest(int id, String title, String detail, int category,int type) {
        QuestList.Quest q=new QuestList.Quest();
        q.setNo(id);q.setTitle(title);q.setDetail(detail);
        q.setState(1);q.setProgressFlag(0);q.setCategory(category);q.setType(type);
        AppQuest a=new AppQuest();a.setNo(id);a.setQuest(q);return a;
    }
    private static String api(String name) {
        return "svdata={\"api_result\":1,\"api_data\":{\"api_count\":1,\"api_page_count\":1,\"api_list\":["+
            "{\"api_no\":987,\"api_title\":\""+name+"\",\"api_detail\":\"2026 西方出撃\","+
            "\"api_state\":1,\"api_progress_flag\":0,\"api_category\":9,\"api_type\":3}]}}";
    }
    private static void capture(QuestStore store) {
        check(store.accept("/kcsapi/api_get_member/questlist",
            Map.of("api_tab_id","0","api_page_no","1"),api("西方南瓜")),"capture");
    }
    private static boolean valid(QuestStore store,List<AppQuest> apps) {
        return Boolean.TRUE.equals(store.validate(apps).get("verified"));
    }
    public static void main(String[] args) {
        List<AppQuest> apps=List.of(quest(987,"西方南瓜","2026 西方出撃",9,3));
        MutableClock clock=new MutableClock("2026-10-23T19:58:00Z"); // 2026-10-24 04:58 JST
        QuestStore store=new QuestStore(clock);
        capture(store);
        check(valid(store,apps),"capture initially verified");
        check(!valid(store,List.of(quest(987,"旧版南瓜","2026 西方出撃",9,3))),"title changes invalidate");
        check(!valid(store,List.of(quest(987,"西方南瓜","古い説明文",9,3))),"description changes invalidate");
        check(!valid(store,List.of(quest(987,"西方南瓜","2026 西方出撃",10,3))),"category changes invalidate");
        check(!valid(store,List.of(quest(987,"西方南瓜","2026 西方出撃",9,2))),"type changes invalidate");
        Map<String,Object> bad=store.validate(List.of(quest(987,"西方南瓜","旧版",9,3)));
        check(((List<?>)bad.get("ids_metadata_mismatch")).contains(987),"mismatch IDs are reported");
        clock.set("2026-10-23T20:00:05Z"); // 05:00:05 JST
        check(!valid(store,apps),"05:00 JST rollover must invalidate even after 125 seconds");
        check("stale".equals(store.validate(apps).get("status")),"rollover explains stale");
        clock.set("2026-10-23T19:59:30Z");
        check(valid(store,apps),"same game day before 05:00 is valid");
        clock.set("2026-10-23T19:57:00Z");
        check(!valid(store,apps),"system time rollback must not verify a future capture");

        MutableClock expiry=new MutableClock("2026-10-24T02:00:00Z");
        QuestStore minutes=new QuestStore(expiry);
        capture(minutes);
        expiry.addMinutes(14);
        check(valid(minutes,apps),"14 minute freshness allowed");
        expiry.addMinutes(1);
        check(!valid(minutes,apps),"15 minutes exactly must expire");

        String fullNames="「Roma」「Luigi Torelli」「伊47」「伊201」「鈴谷」「曙」を使う";
        QuestList.Quest api=quest(987,"任務",fullNames,9,3).getQuest();
        Map<String,Object> nested=Map.of("reset_type","マンスリー",
            "filter",Map.of("fleet",Map.of("operator","AND","conditions",
                List.of(Map.of("ship_names",List.of("Roma","Luigi Torelli","曙")),
                        Map.of("ship_names",List.of("Minneapolis"))))));
        check(QuestExporter.conditionWarnings(api,nested)
            .contains("possible_fleet_name_conflict_check_api_detail"),
            "nested stale name list must be reported");
        QuestList.Quest umbrella=quest(973,"日英米任務",
            "米英艦艇3隻以上を含む空母を伴わない艦隊",9,5).getQuest();
        Map<String,Object> generic=Map.of("filter",Map.of("fleet",
            Map.of("ship_names",List.of("Iowa","Warspite","Nelson"))));
        check(!QuestExporter.conditionWarnings(umbrella,generic)
            .contains("possible_fleet_name_conflict_check_api_detail"),
            "umbrella nationality must not be flagged");
        QuestList.Quest variants=quest(1045,"改装任務",
            "「吹雪改三/改三護(六式)」旗艦、二番艦に特I型",10,5).getQuest();
        Map<String,Object> remodel=Map.of("filter",Map.of("fleet",Map.of("operator","AND",
            "conditions",List.of(Map.of("ship_names",List.of("吹雪改三","吹雪改三護(六式)"))))));
        check(!QuestExporter.conditionWarnings(variants,remodel)
            .contains("possible_fleet_name_conflict_check_api_detail"),
            "slash-separated remodel aliases must not be flagged");

        System.out.println("QuestRegression tests PASS");
    }
}
