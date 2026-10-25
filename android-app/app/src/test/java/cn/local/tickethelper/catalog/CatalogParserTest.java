package cn.local.tickethelper.catalog;

import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CatalogParserTest {
    private static UiItem node(String text, String id, int l, int t, int r, int b) {
        return new UiItem(text,l,t,r,b,true,true,false,UiItem.Source.NODE,"cn.damai:id/" + id);
    }
    private static UiItem ocr(String text, int l, int t, int r, int b) {
        return new UiItem(text,l,t,r,b,true,false,false,UiItem.Source.OCR);
    }
    private static CatalogProject project(String year) {
        return new CatalogProject("project:test", "杭州·谢霆锋演唱会-杭州站", "杭州", year);
    }
    private static List<UiItem> sku(String session) {
        List<UiItem> items = new ArrayList<>();
        items.add(node("杭州·谢霆锋演唱会-杭州站", "title_tv",20,100,900,170));
        items.add(node("", "project_detail_perform_flowlayout",20,300,950,600));
        items.add(node("", "ll_perform_item",30,330,800,450));
        items.add(ocr(session,50,350,760,400));
        items.add(node("", "project_detail_perform_price_flowlayout",20,650,950,1100));
        items.add(node("", "ll_perform_item",30,680,450,810));
        items.add(ocr("980元",60,710,220,760));
        return items;
    }
    private static List<UiItem> suggestions(String query) {
        return new ArrayList<>(List.of(node(query, "header_search_v2_input",20,80,900,180),
                node("", "search_v2_word_suggest_layout",0,200,1000,1600),
                node("", "search_v2_suggest_recycler",0,200,1000,1600),
                node(query, "tv_word",60,230,800,300),
                node("EvolutionNicLive谢霆锋进化演唱会", "tv_word",60,350,900,430)));
    }
    @Test public void exactNativeQuerySuggestionIsSearchActionNotProject() {
        var page = suggestions("谢霆锋");
        var suggestion = CatalogParser.searchSuggestion(page, "谢霆锋");
        assertNotNull(suggestion); assertEquals("谢霆锋", suggestion.target.text);
        assertEquals(230, suggestion.target.top);
        assertTrue(CatalogParser.projects(page, "谢霆锋").isEmpty());
        assertNull(CatalogParser.projectFromPage(page, "谢霆锋"));
    }
    @Test public void suggestionRequiresExactQueryInEditorAndWord() {
        var page = suggestions("谢霆锋 杭州");
        assertNotNull(CatalogParser.searchSuggestion(page, "谢霆锋 杭州"));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋杭州"));
        page.set(3, node("谢霆锋杭州演唱会", "tv_word",60,230,900,300));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋 杭州"));
    }
    @Test public void duplicateOrUnscopedSuggestionsCannotBeSubmitted() {
        var page = suggestions("谢霆锋");
        page.add(node("谢霆锋", "tv_word",60,500,800,570));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋"));
        page = suggestions("谢霆锋"); page.remove(1);
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋"));
        page = suggestions("谢霆锋");
        page.set(3, node("谢霆锋", "tv_word",60,1700,800,1770));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋"));
    }
    @Test public void suggestionsCannotUseOcrOrRestrictedPages() {
        var page = suggestions("谢霆锋"); page.set(3, ocr("谢霆锋",60,230,800,300));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋"));
        page = suggestions("谢霆锋"); page.add(node("请完成验证", "title",20,1800,800,1900));
        assertNull(CatalogParser.searchSuggestion(page, "谢霆锋"));
    }
    @Test public void searchExcludesQueryChipsAndKeepsPublicIdentity() {
        List<UiItem> items = List.of(node("谢霆锋", "search_input",20,60,500,130),
                node("【杭州】EvolutionNicLive谢霆锋进化演唱会-杭州站", "tv_project_name",50,400,900,520),
                node("大家都在搜谢霆锋", "suggestion",50,600,900,700));
        var result = CatalogParser.projects(items, "谢霆锋");
        assertEquals(1,result.size()); assertEquals("杭州",result.get(0).value.city);
        assertEquals("",result.get(0).value.yearHint);
    }
    @Test public void duplicateSameLabelAtDifferentLocationsIsAmbiguous() {
        String title = "【杭州】谢霆锋演唱会";
        assertTrue(CatalogParser.projects(List.of(node(title,"tv_project_name",40,300,900,390),
                node(title,"tv_project_name",40,600,900,690)),"谢霆锋").isEmpty());
    }
    @Test public void ocrDuplicateDoesNotCreateTwoProjects() {
        String title = "【杭州】谢霆锋演唱会";
        assertEquals(1,CatalogParser.projects(List.of(node(title,"tv_project_name",40,300,900,390),
                ocr(title,41,303,896,388)),"谢霆锋").size());
    }
    @Test public void nativeFullTitleWinsOverEllipsizedOcrInSameTitle() {
        String full = "杭州·EvolutionNicLive谢霆锋进化演唱会-杭州站";
        var page = List.of(node(full,"title_tv",40,100,1200,180),
                ocr("杭州·EvolutionNicLive谢霆锋进化演唱会…",42,102,1190,175));
        assertEquals(full,CatalogParser.projectFromPage(page,"谢霆锋").label);
        assertEquals(1,CatalogParser.projects(page,"谢霆锋").size());
    }
    @Test public void observedInfoV2NativeHeaderDoesNotNeedVideoFrameOcr() {
        String title = "【杭州】EvolutionNicLive谢霆锋进化演唱会-杭州站";
        var page = List.of(node(title,"info_v2_title_tv1",40,700,813,850),
                node(title,"info_v2_title_tv2",40,700,813,766),
                node("立即预订","tv_left_main_text",760,2500,972,2562));
        var project = CatalogParser.projectFromPage(page,"谢霆锋 杭州");
        assertNotNull(project); assertEquals(title,project.label); assertEquals("杭州",project.city);
        assertEquals("",project.yearHint);
    }
    @Test public void infoV2HeaderCopiesMustBeLocalAndDifferentTitlesRemainAmbiguous() {
        String title = "【杭州】EvolutionNicLive谢霆锋进化演唱会-杭州站";
        assertNull(CatalogParser.projectFromPage(List.of(node(title,"info_v2_title_tv1",40,700,813,850),
                node(title,"info_v2_title_tv2",40,1700,813,1766)),"谢霆锋"));
        assertNull(CatalogParser.projectFromPage(List.of(node(title,"info_v2_title_tv1",40,700,813,850),
                node(title + "特别场","info_v2_title_tv2",40,700,813,766)),"谢霆锋"));
    }
    @Test public void realInfoV2HeaderFragmentsJoinOnlyAtObservedNativeBoundary() {
        var page = List.of(node("杭州·EvolutionNicLive谢霆锋","info_v2_title_tv1",403,811,1176,961),
                node("进化演唱会-杭州站","info_v2_title_tv2",403,961,1176,1027));
        var parsed = CatalogParser.projectFromPage(page,"谢霆锋 杭州");
        assertNotNull(parsed); assertEquals("杭州·EvolutionNicLive谢霆锋进化演唱会-杭州站",parsed.label);
        assertTrue(CatalogParser.sameProject(new CatalogProject("selected",
                "EvolutionNicLive谢霆锋进化演唱会-杭州站","杭州",""), parsed));
        assertNull(CatalogParser.projectFromPage(List.of(page.get(0),
                node("进化演唱会-杭州站","info_v2_title_tv2",403,970,1176,1036)),"谢霆锋"));
        assertNull(CatalogParser.projectFromPage(List.of(page.get(0),
                node("进化演唱会-杭州站","info_v2_title_tv2",420,961,1193,1027)),"谢霆锋"));
        assertNull(CatalogParser.projectFromPage(List.of(page.get(0),
                node("进化演唱会-温州站","info_v2_title_tv2",403,961,1176,1027)),"谢霆锋"));
    }
    @Test public void sessionsRequireNativeRegionAndNeverGuessCurrentYear() {
        assertTrue(CatalogParser.sessions(List.of(ocr("2026-11-15 19:00",50,350,760,400)),project("")).isEmpty());
        var shortDate = CatalogParser.sessions(sku("11/15 周日 19:00"),project(""));
        assertEquals(1,shortDate.size()); assertEquals("",shortDate.get(0).value.date);
        assertEquals("19:00",shortDate.get(0).value.time);
        assertEquals("2026-11-15",CatalogParser.sessions(sku("11/15 周日 19:00"),project("2026")).get(0).value.date);
    }
    @Test public void fullSessionDateOverridesYearHintAndInvalidDatesStayIncomplete() {
        assertEquals("2027-01-02",CatalogParser.sessions(sku("2027.01.02 19:00"),project("2026")).get(0).value.date);
        assertEquals("",CatalogParser.sessions(sku("2026.02.30 19:00"),project("2026")).get(0).value.date);
        assertEquals("",CatalogParser.sessions(sku("2026.11.15 29:00"),project("2026")).get(0).value.time);
    }
    @Test public void dateRangeCannotBeAppliedAsSingleSession() {
        var result = CatalogParser.sessions(sku("2026.11.14 19:00 2026.11.15 19:00"),project("2026"));
        assertEquals(1,result.size()); assertEquals("",result.get(0).value.date); assertEquals("",result.get(0).value.time);
    }
    @Test public void noStockInferenceFromPriceEnabledOrPresale() {
        var result = CatalogParser.tiers(sku("2026.11.15 19:00"));
        assertEquals(1,result.size()); assertEquals(980,result.get(0).value.price);
        assertEquals("UNKNOWN",result.get(0).value.availability);
        var page = sku("2026.11.15 19:00"); page.add(ocr("预售",250,710,390,760));
        assertEquals("UNKNOWN",CatalogParser.tiers(page).get(0).value.availability);
        page.add(ocr("有票提醒",250,760,430,790));
        assertEquals("UNKNOWN",CatalogParser.tiers(page).get(0).value.availability);
    }
    @Test public void visibleMissingTagOverridesOtherSignalsAndDoesNotLeakAcrossCells() {
        var page = sku("2026.11.15 19:00");
        page.add(node("缺货登记","tv_tag",240,710,420,760));
        page.add(node("","ll_perform_item",470,680,900,810));
        page.add(ocr("1380元 有票",490,710,850,760));
        var result = CatalogParser.tiers(page); assertEquals(2,result.size());
        assertEquals("OUT_OF_STOCK",result.get(0).value.availability);
        assertEquals("IN_STOCK_SIGNAL",result.get(1).value.availability);
    }
    @Test public void excludesTotalsAndBundlePrices() {
        var page = sku("2026.11.15 19:00");
        page.add(node("1999元","tv_total",40,2000,400,2090));
        page.add(ocr("双人套票",230,710,440,760));
        assertTrue(CatalogParser.tiers(page).isEmpty());
    }
    @Test public void checkoutAndVerificationFailClosed() {
        var page = sku("2026.11.15 19:00"); page.add(node("确认购买","title",20,40,400,90));
        assertEquals("private_or_checkout_page",CatalogParser.blockedReason(page));
        assertTrue(CatalogParser.tiers(page).isEmpty());
        assertEquals("login_verification_or_network",CatalogParser.blockedReason(List.of(ocr("请完成验证",30,30,300,100))));
    }
    @Test public void tourYearIsNotSessionYearEvidence() {
        var item = node("【杭州】2026谢霆锋演唱会", "title_tv",30,100,950,200);
        assertEquals("",CatalogParser.projectFromPage(List.of(item),"谢霆锋").yearHint);
    }
    @Test public void pageYearOnlyUsesTargetSessionRegion() {
        var page = sku("2026.11.15 19:00");
        assertEquals("2026",CatalogParser.projectFromPage(page,"谢霆锋").yearHint);
        page.add(ocr("2027.01.01",50,1500,500,1600));
        assertEquals("2026",CatalogParser.projectFromPage(page,"谢霆锋").yearHint);
        page = sku("11/15 19:00"); page.add(ocr("2027.01.01",50,1500,500,1600));
        assertEquals("",CatalogParser.projectFromPage(page,"谢霆锋").yearHint);
    }
    @Test public void publicGuidanceAboutAttendeesDoesNotMasqueradeAsCheckout() {
        var page = sku("2026.11.15 19:00"); page.add(ocr("预选本次实名抢票观演人",50,1500,900,1600));
        assertEquals("",CatalogParser.blockedReason(page));
        assertEquals(1,CatalogParser.sessions(page,project("2026")).size());
    }
    @Test public void observedSearchTourGroupsResolveEachCityNotArticleOrArtist() throws Exception {
        List<UiItem> page = fixture("/catalog/xie-search-visible.tsv");
        var projects = CatalogParser.projects(page,"谢霆锋");
        assertEquals(4,projects.size());
        var hangzhou = projects.stream().filter(p -> p.value.city.equals("杭州")).findFirst().orElseThrow();
        assertEquals("EvolutionNicLive谢霆锋进化演唱会-杭州站",hangzhou.value.label);
        assertEquals("cn.damai:id/ll_city_tour",hangzhou.target.resourceId);
        assertEquals(455,hangzhou.target.left); assertEquals(730,hangzhou.target.top);
        assertEquals("",hangzhou.value.yearHint);
        assertNull(CatalogParser.projectFromPage(page,"谢霆锋"));
    }
    @Test public void cityCannotBeAttachedWithoutItsSingleScopedTourTitle() throws Exception {
        List<UiItem> page = fixture("/catalog/xie-search-visible.tsv");
        page.removeIf(item -> item.resourceId.endsWith("/contentLayout"));
        assertTrue(CatalogParser.projects(page,"谢霆锋").isEmpty());
    }
    @Test public void detailIdentityMayOnlyAddKnownCityDecoration() {
        var search = new CatalogProject("a","EvolutionNicLive谢霆锋进化演唱会-杭州站","杭州","");
        var sku = new CatalogProject("b","杭州·EvolutionNicLive谢霆锋进化演唱会-杭州站","杭州","");
        assertTrue(CatalogParser.sameProject(search,sku));
        var special = new CatalogProject("c","杭州·EvolutionNicLive谢霆锋进化演唱会-杭州站特别场","杭州","");
        assertFalse(CatalogParser.sameProject(search,special));
        var elsewhere = new CatalogProject("d","温州·EvolutionNicLive谢霆锋进化演唱会-温州站","温州","");
        assertFalse(CatalogParser.sameProject(search,elsewhere));
    }
    @Test public void actualSkuFixtureReadsTwoSessionsAndSixPricesWithoutInventingStock() throws Exception {
        var page = fixture("/catalog/xie-tiers-visible.tsv");
        var event = CatalogParser.projectFromPage(page,"谢霆锋");
        assertNotNull(event); assertEquals("杭州",event.city);
        assertTrue(event.label.endsWith("-杭州站"));
        var sessions = CatalogParser.sessions(page,event);
        assertEquals(2,sessions.size()); assertEquals("2026-11-15",sessions.get(1).value.date);
        assertEquals("19:00",sessions.get(1).value.time);
        var tiers = CatalogParser.tiers(page);
        assertEquals(6,tiers.size());
        assertEquals(List.of(380,580,780,980,1380,1680),tiers.stream().map(t -> t.value.price).collect(java.util.stream.Collectors.toList()));
        for (var tier : tiers) assertEquals(tier.value.price == 980 ? "UNKNOWN" : "OUT_OF_STOCK",tier.value.availability);
    }
    @Test public void knownHomepageSearchButtonWinsEvenWhenTextIsEmpty() {
        UiItem button = node("", "pioneer_homepage_header_search_btn", 900,150,1180,285);
        UiItem layout = node("", "homepage_header_search_layout", 200,144,1220,295);
        assertSame(button,CatalogParser.searchEntry(List.of(layout,button,ocr("推荐词 搜索",250,170,1150,270))));
    }
    @Test public void searchEntryRejectsWrongSourceAmbiguityAndPrivatePages() {
        UiItem button = node("", "pioneer_homepage_header_search_btn", 900,150,1180,285);
        assertNull(CatalogParser.searchEntry(List.of(button,node("", "pioneer_homepage_header_search_btn", 900,400,1180,500))));
        assertNull(CatalogParser.searchEntry(List.of(button,node("确认购买","title_tv",20,20,600,100))));
        assertNull(CatalogParser.searchEntry(List.of(new UiItem("",900,150,1180,285,true,true,false,UiItem.Source.OCR,button.resourceId))));
    }
    @Test public void nativeSearchResultsAcceptNaturalArtistCityQueriesWithoutChangingProjectIdentity() throws Exception {
        var page = fixture("/catalog/xie-search-visible.tsv");
        var spaced = CatalogParser.projects(page,"谢霆锋 杭州");
        var joined = CatalogParser.projects(page,"谢霆锋杭州演唱会");
        assertEquals(4,spaced.size()); assertEquals(4,joined.size());
        var chosen = spaced.stream().filter(entry -> entry.value.city.equals("杭州")).findFirst().orElseThrow().value;
        var destination = CatalogParser.projectFromPage(fixture("/catalog/xie-tiers-visible.tsv"),"谢霆锋 杭州");
        assertTrue(CatalogParser.sameProject(chosen,destination));
    }
    @Test public void standardNativeCardRequiresItsScopeAndReadsExplicitCityWithoutQuerySubstring() {
        var page = new ArrayList<UiItem>();
        page.add(node("谢霆锋 杭州","header_search_v2_input",20,100,900,200));
        page.add(node("","ll_search_item",20,300,1100,700));
        page.add(node("EvolutionNicLive谢霆锋进化演唱会","tv_project_name",100,340,1000,430));
        page.add(node("杭州 | ","tv_project_city",100,500,500,560));
        page.add(node("谢霆锋其他演唱会","tv_project_name",100,800,1000,900));
        var result = CatalogParser.projects(page,"谢霆锋 杭州");
        assertEquals(1,result.size()); assertEquals("杭州",result.get(0).value.city);
        assertEquals("EvolutionNicLive谢霆锋进化演唱会",result.get(0).value.label);
        assertTrue(CatalogParser.sameProject(result.get(0).value,new CatalogProject("x","杭州·EvolutionNicLive谢霆锋进化演唱会-杭州站","杭州","")));
    }
    @Test public void merchandisingCardDoesNotBecomeConcertChoice() {
        var page = List.of(node("谢霆锋","header_search_v2_input",20,100,900,200),
                node("","ll_search_item",20,300,1100,700),
                node("谢霆锋演唱会官方荧光棒","tv_project_name",100,340,1000,430),
                node("杭州 | ","tv_project_city",100,500,500,560));
        assertTrue(CatalogParser.projects(page,"谢霆锋 杭州").isEmpty());
    }
    private static List<UiItem> fixture(String name) throws Exception {
        List<UiItem> page = new ArrayList<>();
        try (var in = CatalogParserTest.class.getResourceAsStream(name);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split("\\t",-1), bounds = row[2].split(",");
                page.add(new UiItem(row[1],Integer.parseInt(bounds[0]),Integer.parseInt(bounds[1]),Integer.parseInt(bounds[2]),Integer.parseInt(bounds[3]),
                        Boolean.parseBoolean(row[3]),Boolean.parseBoolean(row[4]),false,
                        row.length > 5 ? UiItem.Source.valueOf(row[5]) : UiItem.Source.NODE,row[0]));
            }
        }
        return page;
    }
}
