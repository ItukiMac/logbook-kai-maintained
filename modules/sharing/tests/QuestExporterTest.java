package logbook.features.sharing;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import logbook.bean.AppQuest;
import logbook.bean.QuestList;

/** Verifies full quest list, completeness disclaimers, metadata, and privacy. */
public final class QuestExporterTest {
    private static AppQuest makeQuest(int id, String title, int state, int progress) {
        QuestList.Quest q = new QuestList.Quest();
        q.setNo(id);
        q.setTitle(title);
        q.setDetail("指定海域でS勝利する");
        q.setCategory(2);
        q.setType(4);
        q.setState(state);
        q.setProgressFlag(progress);
        q.setGetMaterial(List.of(0, 100, 200, 1));
        q.setBonusFlag(1);
        q.setInvalidFlag(0);
        AppQuest a = new AppQuest();
        a.setNo(id);
        a.setQuest(q);
        a.setActive(state==2 || state==3);
        a.setExpire(null);
        return a;
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        QuestStore store = new QuestStore();
        List<AppQuest> all = List.of(
            makeQuest(11,"未受注任務",1,0),
            makeQuest(12,"受注中任務",2,1),
            makeQuest(13,"達成・未受領任務",3,2));

        Map<String,Object> before = QuestExporter.build(store, all, false);
        JsonNode initial = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(before));
        check(initial.path("quests").size()==3, "not only accepted tasks");
        check(initial.path("condition_coverage").path("local_condition_missing").asInt()==3,"condition coverage missing");
        check(initial.path("condition_coverage").path("local_condition_warning_count").asInt()==0,"condition coverage warnings");
        check(initial.path("counts").path("available_unaccepted").asInt()==1,"unaccepted count");
        check(initial.path("counts").path("accepted").asInt()==1,"accepted count");
        check(initial.path("counts").path("completed_unclaimed").asInt()==1,"completed count");
        check(!initial.path("all_tab_current_session").asBoolean(),"missing all-tab evidence");
        check(!initial.path("snapshot_verified").asBoolean(),"not initially verified");
        check("not_observed".equals(initial.path("completeness").path("status").asText()),"unverified reason");
        check(initial.path("quests").get(0).path("basic_reward_materials").size()==4,"reward materials");
        check(initial.path("quests").get(1).path("progress_stage").asText().contains("50"),"progress stage");
        check(initial.path("quests").get(0).path("prerequisite_quest_ids").isNull(),"unknown prerequisites explicit");
        check(initial.path("quests").get(0).path("choice_reward_details").isNull(),"unknown choice reward explicit");

        String example="svdata={\"api_result\":1,\"api_data\":{\"api_count\":3,\"api_exec_count\":2,\"api_page_count\":1,\"api_list\":[{\"api_no\":11,\"api_title\":\"未受注任務\",\"api_state\":1}]}}";
        check(store.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","0","api_page_no","1"),example),"all-tab capture");
        JsonNode observed = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(QuestExporter.build(store,all,false)));
        check(observed.path("all_tab_current_session").asBoolean(),"all-tab observed");
        check(!observed.path("snapshot_verified").asBoolean(),"one item for API count 3 is not verified");
        check("incomplete_api_snapshot".equals(observed.path("completeness").path("status").asText()),"API count mismatch");
        check(observed.path("capture").path("api_count").asInt()==3,"api total count");
        check(observed.path("capture").path("api_exec_count").asInt()==2,"accepted total count");
        check(!observed.toString().contains("api_token"),"no POST auth parameter");
        check(!observed.toString().contains("SECRET"),"no secret value");
        check(observed.path("scope").asText().contains("logbook_kai"),"source scope");

        QuestStore complete=new QuestStore();
        String full="svdata={\"api_result\":1,\"api_data\":{\"api_count\":3,\"api_exec_count\":2,\"api_list\":["+
            "{\"api_no\":11,\"api_title\":\"未受注任務\",\"api_state\":1,\"api_progress_flag\":0},"+
            "{\"api_no\":12,\"api_title\":\"受注中任務\",\"api_state\":2,\"api_progress_flag\":1},"+
            "{\"api_no\":13,\"api_title\":\"達成・未受領任務\",\"api_state\":3,\"api_progress_flag\":2}]}}";
        check(complete.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","0","api_page_no","1"),full),"full API tab");
        JsonNode valid = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(QuestExporter.build(complete,all,false)));
        check(valid.path("snapshot_verified").asBoolean(),"verified all tab matching IDs, states and progress");
        check(valid.path("capture").path("observed_page_keys").get(0).asText().equals("0:1"),"page evidence preserved");
        QuestList.Quest inconsistent=makeQuest(12,"別の状態",1,1).getQuest();
        List<AppQuest> changed = List.of(all.get(0),makeQuest(12,"状態不一致",1,1),all.get(2));
        JsonNode invalid = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(QuestExporter.build(complete,changed,false)));
        check(!invalid.path("snapshot_verified").asBoolean(),"reject stale collection state");
        check(invalid.path("completeness").path("ids_state_or_progress_mismatch").size()==1,"mismatch ID included");
        check(complete.accept("/kcsapi/api_req_quest/start",Map.of("api_quest_id","12"),"svdata={\"api_result\":1,\"api_data\":{}}"),"subsequent mutation");
        JsonNode mutated = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(QuestExporter.build(complete,all,false)));
        check("mutation_since_capture".equals(mutated.path("completeness").path("status").asText()),"dirty after quest action");

        QuestList.Quest ten=makeQuest(14,"出撃カテゴリ10",1,0).getQuest();
        ten.setCategory(10);
        QuestList.Quest eleven=makeQuest(15,"工廠カテゴリ11",1,0).getQuest();
        eleven.setCategory(11);
        AppQuest a10=new AppQuest();a10.setNo(14);a10.setQuest(ten);
        AppQuest a11=new AppQuest();a11.setNo(15);a11.setQuest(eleven);
        JsonNode category = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build().readTree(QuestExporter.encode(QuestExporter.build(new QuestStore(),List.of(a10,a11),false)));
        check("sortie".equals(category.path("quests").get(0).path("category").asText()),"category 10 sortie");
        check("factory".equals(category.path("quests").get(1).path("category").asText()),"category 11 factory");

        QuestList.Quest api=makeQuest(987,"期間限定",1,0).getQuest();
        api.setType(3);api.setDetail("「鈴谷」「朧」を使う");
        Map<String,Object> local=Map.of("reset_type","ウィークリー","filter",Map.of("fleet",Map.of("ship_names",List.of("鈴谷","Minneapolis"))));
        var warnings=QuestExporter.conditionWarnings(api,local);
        check(warnings.contains("api_local_recurrence_conflict"),"period mismatch flagged");
        check(warnings.contains("possible_fleet_name_conflict_check_api_detail"),"fleet conflict flagged");
        check(QuestExporter.conditionWarnings(api,null).isEmpty(),"null condition safe");
        QuestStore paged=new QuestStore();
        String first="svdata={\"api_result\":1,\"api_data\":{\"api_count\":3,\"api_page_count\":2,\"api_list\":["+
            "{\"api_no\":11,\"api_state\":1,\"api_progress_flag\":0},"+
            "{\"api_no\":12,\"api_state\":2,\"api_progress_flag\":1}]}}";
        String second="svdata={\"api_result\":1,\"api_data\":{\"api_count\":3,\"api_page_count\":2,\"api_list\":["+
            "{\"api_no\":13,\"api_state\":3,\"api_progress_flag\":2}]}}";
        check(paged.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","0","api_page_no","1"),first),"paged 1");
        check(!Boolean.TRUE.equals(paged.validate(all).get("verified")),"page 1 must be incomplete");
        check(paged.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","0","api_page_no","2"),second),"paged 2");
        check(Boolean.TRUE.equals(paged.validate(all).get("verified")),"two pages collectively verified");
        check(paged.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","1","api_page_no","1"),
            "svdata={\"api_result\":1,\"api_data\":{\"api_count\":999,\"api_list\":[]}}"),"non-all tab");
        check(Boolean.TRUE.equals(paged.validate(all).get("verified")),"non-all tab cannot corrupt all-tab count");
        check(paged.accept("/kcsapi/api_get_member/questlist",Map.of("api_tab_id","0","api_page_no","1"),first),"new generation");
        check(!Boolean.TRUE.equals(paged.validate(all).get("verified")),"new page1 clears stale page2");
        check(((Number)paged.captureEvidence().get("observed_all_tab_quest_ids")).intValue()==2,"page1 reset");

        System.out.println("QuestExporter tests PASS");
    }
}
