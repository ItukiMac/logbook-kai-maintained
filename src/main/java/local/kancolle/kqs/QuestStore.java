package local.kancolle.kqs;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;

/** Token-free in-memory quest state. All methods are synchronized. */
public final class QuestStore {
    private record Quest(int id,String title,String detail,int state,int progress,Instant seen) {}
    private final Clock clock;
    public QuestStore() { this(Clock.systemUTC()); }
    QuestStore(Clock clock) { this.clock=Objects.requireNonNull(clock); }
    private final Map<Integer,Quest> quests=new LinkedHashMap<>();
    private final Map<String,Instant> pages=new LinkedHashMap<>();
    // Raw API snapshot, used only to validate the logbook-kai AppQuestCollection.
    private final Map<Integer,Integer> allTabStates=new LinkedHashMap<>();
    private final Map<Integer,Integer> allTabProgress=new LinkedHashMap<>();
    private final Map<Integer,String> allTabTitles=new LinkedHashMap<>();
    private final Map<Integer,String> allTabDetails=new LinkedHashMap<>();
    private final Map<Integer,Integer> allTabTypes=new LinkedHashMap<>();
    private final Map<Integer,Integer> allTabCategories=new LinkedHashMap<>();
    private Instant lastQuestMutation;
    private int observedAllPages;
    private boolean allTabHasUnnumberedPage;
    private Instant lastUpdate,lastList;
    private Integer reportedAccepted;
    private Integer reportedTotal;
    private Integer reportedPages;
    private Integer allTabReportedTotal;
    private Integer allTabReportedPages;
    private Instant lastAllTab;
    private String lastTab;
    private int acceptedEvents;

    public synchronized boolean accept(String path,Map<String,String> params,String body) {
        if(path==null||body==null||body.length()>2_000_000)return false;
        String kind=switch(path){
            case "/kcsapi/api_get_member/questlist"->"list";
            case "/kcsapi/api_req_quest/start"->"start";
            case "/kcsapi/api_req_quest/stop"->"stop";
            case "/kcsapi/api_req_quest/clearitemget"->"clear";
            default->null;
        };
        if(kind==null)return false;
        Object raw;
        try{raw=Json.parse(body.startsWith("svdata=")?body.substring(7):body);}
        catch(RuntimeException error){return false;}
        if(!(raw instanceof Map<?,?> response)||number(response.get("api_result"),0)!=1)return false;
        Object data=response.get("api_data"); Instant now=clock.instant();
        if(kind.equals("list")){
            if(!(data instanceof Map<?,?> detail)||!(detail.get("api_list") instanceof List<?> list))return false;
            int page=parseInt(params==null?null:params.get("api_page_no"),1);
            String tab=strip(params==null?"all":params.getOrDefault("api_tab_id","all"),20);
            lastList=now;lastTab=tab;
            if ("0".equals(tab)) {
                // Page 1 begins a new capture generation. Never mix previous pages.
                if (page==1) {
                    allTabStates.clear();
                    allTabProgress.clear();
                    allTabTitles.clear();
                    allTabDetails.clear();
                    allTabTypes.clear();
                    allTabCategories.clear();
                    observedAllPages=0;
                    allTabHasUnnumberedPage=false;
                    allTabReportedTotal=null;
                    allTabReportedPages=null;
                    pages.keySet().removeIf(k->k.startsWith("0:"));
                }
                lastAllTab=now;
                observedAllPages++;
                pages.put(tab+":"+page,now);
                if (params==null || !params.containsKey("api_page_no")) allTabHasUnnumberedPage=true;
            }
            Integer total=optionalNumber(detail.get("api_count"));
            if(total!=null) {reportedTotal=total;if("0".equals(tab))allTabReportedTotal=total;}
            Integer count=optionalNumber(detail.get("api_page_count"));
            if(count!=null) {reportedPages=count;if("0".equals(tab))allTabReportedPages=count;}
            Integer reported=optionalNumber(detail.get("api_exec_count"));
            if(reported!=null)reportedAccepted=reported;
            for(Object obj:list){
                if(!(obj instanceof Map<?,?> q))continue;
                int id=number(q.get("api_no"),-1);if(id<1||id>9_999_999)continue;
                Quest old=quests.get(id);
                String title=strip(q.get("api_title"),200);
                String description=strip(q.get("api_detail"),700);
                if(title.isEmpty())title=old==null?"任務 #"+id:old.title;
                if(description.isEmpty()&&old!=null)description=old.detail;
                int state=number(q.get("api_state"),old==null?1:old.state);
                int progress=number(q.get("api_progress_flag"),old==null?0:old.progress);
                quests.put(id,new Quest(id,title,description,state,progress,now));
                if ("0".equals(tab)) {
                    allTabStates.put(id,state);
                    allTabProgress.put(id,progress);
                    if(q.get("api_title")!=null)allTabTitles.put(id,strip(q.get("api_title"),200));
                    if(q.get("api_detail")!=null)allTabDetails.put(id,strip(q.get("api_detail"),700));
                    if(q.get("api_type")!=null)allTabTypes.put(id,number(q.get("api_type"),-1));
                    if(q.get("api_category")!=null)allTabCategories.put(id,number(q.get("api_category"),-1));
                }
            }
        }else{
            lastQuestMutation=now;
            int id=parseInt(params==null?null:params.get("api_quest_id"),-1);
            if(id<1||id>9_999_999)return false;
            Quest prev=quests.getOrDefault(id,new Quest(id,"任務 #"+id,"",1,0,now));
            int state=kind.equals("start")?2:kind.equals("stop")?1:0;
            quests.put(id,new Quest(id,prev.title,prev.detail,state,prev.progress,now));
        }
        lastUpdate=now;acceptedEvents++;
        if(quests.size()>400)quests.values().stream().filter(q->q.state!=2&&q.state!=3)
            .min(Comparator.comparing(Quest::seen)).ifPresent(q->quests.remove(q.id));
        return true;
    }

    public synchronized Map<String,Object> captureEvidence() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("quest_list_observed", lastList!=null);
        data.put("all_tab_observed_this_session", lastAllTab!=null);
        data.put("last_list_at", lastList==null?null:lastList.toString());
        data.put("last_all_tab_at", lastAllTab==null?null:lastAllTab.toString());
        data.put("last_tab_id", lastTab);
        data.put("api_count", allTabReportedTotal==null?reportedTotal:allTabReportedTotal);
        data.put("api_page_count", allTabReportedPages==null?reportedPages:allTabReportedPages);
        data.put("api_exec_count", reportedAccepted);
        data.put("observed_page_keys", List.copyOf(pages.keySet()));
        data.put("observed_quest_events", acceptedEvents);
        data.put("observed_all_tab_quest_ids", allTabStates.size());
        data.put("observed_all_tab_pages", observedAllPages);
        data.put("all_tab_page_number_missing", allTabHasUnnumberedPage);
        data.put("last_quest_mutation_at", lastQuestMutation==null?null:lastQuestMutation.toString());
        return data;
    }
    /**
     * Evaluate the raw game ALL-tab snapshot against the logbook-kai collection.
     * Never report verified without ID + state matches and bounded freshness.
     */
    public synchronized Map<String,Object> validate(Collection<logbook.bean.AppQuest> apps) {
        Map<Integer,Integer> actual=new LinkedHashMap<>();
        Map<Integer,Integer> progress=new LinkedHashMap<>();
        Map<Integer,String> titles=new LinkedHashMap<>();
        Map<Integer,String> descriptions=new LinkedHashMap<>();
        Map<Integer,Integer> types=new LinkedHashMap<>();
        Map<Integer,Integer> categories=new LinkedHashMap<>();
        for (logbook.bean.AppQuest app:apps) {
            if(app==null || app.getQuest()==null || app.getQuest().getNo()==null) continue;
            logbook.bean.QuestList.Quest q=app.getQuest();
            actual.put(q.getNo(), q.getState());
            progress.put(q.getNo(), q.getProgressFlag());
            titles.put(q.getNo(),strip(q.getTitle(),200));
            descriptions.put(q.getNo(),strip(q.getDetail(),700));
            types.put(q.getNo(),q.getType());
            categories.put(q.getNo(),q.getCategory());
        }
        List<Integer> missing=new ArrayList<>();
        List<Integer> extra=new ArrayList<>();
        List<Integer> changed=new ArrayList<>();
        List<Integer> changedMetadata=new ArrayList<>();
        for (Integer id:allTabStates.keySet()) {
            if(!actual.containsKey(id)) missing.add(id);
            else if(!Objects.equals(actual.get(id),allTabStates.get(id)) ||
                    !Objects.equals(progress.get(id),allTabProgress.get(id))) changed.add(id);
            else if ((allTabTitles.containsKey(id) && !Objects.equals(titles.get(id),allTabTitles.get(id))) ||
                (allTabDetails.containsKey(id) && !Objects.equals(descriptions.get(id),allTabDetails.get(id))) ||
                (allTabTypes.containsKey(id) && !Objects.equals(types.get(id),allTabTypes.get(id))) ||
                (allTabCategories.containsKey(id) && !Objects.equals(categories.get(id),allTabCategories.get(id))))
                changedMetadata.add(id);
        }
        for(Integer id:actual.keySet())if(!allTabStates.containsKey(id)) extra.add(id);
        boolean allSeen=lastAllTab!=null;
        boolean apiSizeMatches=allTabReportedTotal!=null && allTabReportedTotal==allTabStates.size();
        Instant now=clock.instant();
        Duration age=allSeen?Duration.between(lastAllTab,now):null;
        boolean notExpired=allSeen && !age.isNegative() && age.compareTo(Duration.ofMinutes(15))<0;
        boolean pagesComplete=true;
        if(allTabReportedPages!=null && allTabReportedPages>0) {
            for(int n=1;n<=allTabReportedPages;n++)if(!pages.containsKey("0:"+n))pagesComplete=false;
        }
        boolean noNewMutation=allSeen && (lastQuestMutation==null || !lastQuestMutation.isAfter(lastAllTab));
        // Game daily reset at 05:00 JST invalidates a previously captured snapshot.
        ZoneId jst=ZoneId.of("Asia/Tokyo");
        boolean sameGameDay=allSeen &&
            lastAllTab.atZone(jst).minusHours(5).toLocalDate()
                                .equals(now.atZone(jst).minusHours(5).toLocalDate());
        String verdict;
        if (!allSeen) verdict="not_observed";
        else if (!apiSizeMatches || !pagesComplete) verdict="incomplete_api_snapshot";
        else if(!missing.isEmpty() || !extra.isEmpty() || !changed.isEmpty() || !changedMetadata.isEmpty()) verdict="logbook_api_mismatch";
        else if(!noNewMutation) verdict="mutation_since_capture";
        else if(!notExpired || !sameGameDay) verdict="stale";
        else verdict="verified";
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("status",verdict);
        evidence.put("verified", "verified".equals(verdict));
        evidence.put("all_tab_snapshot_count",allTabStates.size());
        evidence.put("logbook_collection_count",actual.size());
        evidence.put("api_reported_count",allTabReportedTotal);
        evidence.put("api_count_matches_snapshot",apiSizeMatches);
        evidence.put("ids_missing_in_logbook",missing);
        evidence.put("ids_extra_in_logbook",extra);
        evidence.put("ids_state_or_progress_mismatch",changed);
        evidence.put("ids_metadata_mismatch",changedMetadata);
        evidence.put("all_tab_page_coverage_complete",pagesComplete);
        evidence.put("freshness_max_minutes",15);
        evidence.put("fresh_within_15_minutes",notExpired);
        evidence.put("same_game_day",sameGameDay);
        evidence.put("no_quest_mutation_since_capture",noNewMutation);
        evidence.put("all_tab_page_count_reported",allTabReportedPages);
        evidence.put("all_tab_page_numbers_unavailable",allTabHasUnnumberedPage);
        return evidence;
    }

    public synchronized int activeCount(){
        return (int)quests.values().stream().filter(q->q.state==2||q.state==3).count();
    }
    public synchronized int acceptedEvents(){return acceptedEvents;}
    public synchronized String status(){
        long completed=quests.values().stream().filter(q->q.state==3).count();
        return "受注/達成 "+activeCount()+"（達成"+completed+"） | 取得ページ "+pages.size()
            +" | 最終取得 "+(lastUpdate==null?"なし":lastUpdate.toString());
    }
    public synchronized String prompt(boolean includeUnaccepted){
        List<Quest> active=quests.values().stream().filter(q->q.state==2||q.state==3)
            .sorted(Comparator.comparingInt(Quest::id)).toList();
        StringBuilder b=new StringBuilder();
        b.append("艦これの任務受注状況です。任務の優先順位と並行消化を整理してください。期限付き・恒常入手困難な報酬を優先し、資源・戦果中心の報酬は低優先としてください。条件はwikiwiki.jp/kancolle/とzekamashi.netで確認してください。\n\n");
        b.append("取得日時: ").append(lastUpdate==null?"未取得":lastUpdate).append('\n');
        b.append("任務一覧の最終取得: ").append(lastList==null?"未取得":lastList).append('\n');
        b.append("表示済みページ: ").append(pages.size()).append(" / 受注・達成（未受領）: ").append(active.size());
        if(reportedAccepted!=null)b.append(" / API受注数: ").append(reportedAccepted);
        b.append("\n注意: 任務一覧を表示していないページ・過去に取得したデータは不完全・古い場合があります。未表示を未受注と断定しないでください。進捗50%/80%は段階表示です。\n\n");
        b.append("【受注中・達成未受領】\n");
        if(active.isEmpty())b.append("（未取得。ゲームの任務一覧を開いてください）\n");
        for(Quest q:active)appendQuest(b,q,includeUnaccepted);
        if(includeUnaccepted){
            b.append("\n【観測済み未受注】\n");
            quests.values().stream().filter(q->q.state==1)
                .sorted(Comparator.comparingInt(Quest::id)).forEach(q->appendQuest(b,q,true));
        }
        return b.toString();
    }
    private static void appendQuest(StringBuilder b,Quest q,boolean detailed){
        b.append("- #").append(q.id).append(' ').append(q.title).append(" | ");
        b.append(q.state==3?"達成（未受領）":q.state==2?"受注中":"未受注");
        if(q.state!=3&&q.state!=1)b.append(q.progress==2?" 80%以上":q.progress==1?" 50%以上":" 進捗表示なし");
        if(detailed&&!q.detail.isBlank())b.append(" | ").append(q.detail);
        b.append(" | 確認").append(q.seen).append('\n');
    }
    private static int parseInt(String str,int fallback){
        try{int x=Integer.parseInt(str);return x<0?fallback:x;}catch(RuntimeException e){return fallback;}
    }
    private static int number(Object value,int fallback){
        Integer n=optionalNumber(value);return n==null?fallback:n;
    }
    private static Integer optionalNumber(Object value){
        try{return value==null?null:Integer.valueOf(value.toString());}
        catch(RuntimeException e){return null;}
    }
    private static String strip(Object value,int max){
        if(!(value instanceof String))return "";
        String text=((String)value).replaceAll("(?i)<br\\s*/?>"," ").replaceAll("<[^>]*>","")
            .replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();
        return text.substring(0,Math.min(max,text.length()));
    }

    /** Self-contained limited JSON reader: no network or external dependency. */
    static final class Json {
        private final String s; private int p;
        private Json(String s){this.s=s;}
        static Object parse(String s){
            Json j=new Json(s);Object v=j.value(0);j.space();
            if(j.p!=s.length())throw new IllegalArgumentException("json trailing content");
            return v;
        }
        private void space(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
        private char peek(){if(p>=s.length())throw new IllegalArgumentException("json eof");return s.charAt(p);}
        private void want(char c){space();if(peek()!=c)throw new IllegalArgumentException("json expected "+c);p++;}
        private Object value(int depth){
            if(depth>64)throw new IllegalArgumentException("json nesting");
            space();char c=peek();
            if(c=='{'){
                p++;Map<String,Object> m=new LinkedHashMap<>();space();
                if(peek()=='}'){p++;return m;}
                do{
                    space();String k=string();want(':');m.put(k,value(depth+1));space();
                    char end=peek();p++;if(end=='}')return m;
                    if(end!=',')throw new IllegalArgumentException("json object comma");
                }while(true);
            }
            if(c=='['){
                p++;List<Object> a=new ArrayList<>();space();
                if(peek()==']'){p++;return a;}
                do{
                    a.add(value(depth+1));space();char end=peek();p++;
                    if(end==']')return a;
                    if(end!=',')throw new IllegalArgumentException("json array comma");
                }while(true);
            }
            if(c=='"')return string();
            if(s.startsWith("true",p)){p+=4;return true;}
            if(s.startsWith("false",p)){p+=5;return false;}
            if(s.startsWith("null",p)){p+=4;return null;}
            int start=p;
            if(c=='-')p++;
            while(p<s.length()&&("0123456789.eE+-".indexOf(s.charAt(p))>=0))p++;
            if(p==start)throw new IllegalArgumentException("json value");
            String token=s.substring(start,p);
            if(!token.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?"))throw new IllegalArgumentException("json number");
            return new java.math.BigDecimal(token);
        }
        private String string(){
            want('"');StringBuilder b=new StringBuilder();
            while(true){
                char c=peek();p++;
                if(c=='"')return b.toString();
                if(c<32)throw new IllegalArgumentException("json control");
                if(c!='\\'){b.append(c);continue;}
                char e=peek();p++;
                switch(e){
                    case '"','\\','/'->b.append(e);
                    case 'b'->b.append('\b');
                    case 'f'->b.append('\f');
                    case 'n'->b.append('\n');
                    case 'r'->b.append('\r');
                    case 't'->b.append('\t');
                    case 'u'->{
                        if(p+4>s.length())throw new IllegalArgumentException("json unicode");
                        b.append((char)Integer.parseInt(s.substring(p,p+4),16));p+=4;
                    }
                    default->throw new IllegalArgumentException("json escape");
                }
            }
        }
    }
}
