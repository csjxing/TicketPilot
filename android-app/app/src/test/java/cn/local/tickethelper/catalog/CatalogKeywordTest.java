package cn.local.tickethelper.catalog;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CatalogKeywordTest {
    private static final String TITLE = "【杭州】EvolutionNicLive谢霆锋进化演唱会-杭州站";
    @Test public void naturalArtistAndCityDoesNotRequireWholeSentenceInTitle() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "谢霆锋 杭州", "杭州"));
    }
    @Test public void concatenatedCityAndTicketWordsAreRemoved() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "谢霆锋杭州演唱会门票", "杭州"));
    }
    @Test public void longestSubstantiveQueryTokenWins() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "进化 谢霆锋 杭州", "杭州"));
    }
    @Test public void datesTimesAndAmountsDoNotBecomeIdentity() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "2026年11月15日 谢霆锋 杭州 19:00 980元 1张", "杭州"));
    }
    @Test public void cityAndGenericWordsAloneCannotBecomeIdentity() {
        assertNull(CatalogKeyword.fromQuery(TITLE, "杭州 演唱会 门票 2026-11-15 19:00", "杭州"));
    }
    @Test public void partialCityCannotBecomeIdentity() {
        assertNull(CatalogKeyword.fromQuery("【杭州市】音乐会", "杭州", "杭州市"));
    }
    @Test public void englishAndWidthChangesReturnActualTitleSubstring() {
        assertEquals("EvolutionNicLive", CatalogKeyword.fromQuery(TITLE, "ＥＶＯＬＵＴＩＯＮＮＩＣＬＩＶＥ 杭州", "杭州"));
    }
    @Test public void punctuationInsideVisibleNameIsPreserved() {
        assertEquals("Beyond · Live", CatalogKeyword.fromQuery("【杭州】Beyond · Live 演唱会", "beyondlive 杭州", "杭州"));
    }
    @Test public void hyphenatedNameIsNotShortenedToTheLastWord() {
        assertEquals("A-Lin", CatalogKeyword.fromQuery("【杭州】A-Lin 世界巡回演唱会", "a-lin 杭州", "杭州"));
    }
    @Test public void punctuationSeparatedQueriesCanUseOneExactName() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "杭州，谢霆锋，演唱会", "杭州"));
    }
    @Test public void noArtistAliasOrTypoMappingIsInvented() {
        assertNull(CatalogKeyword.fromQuery(TITLE, "霆锋哥 杭州", "杭州"));
        assertNull(CatalogKeyword.fromQuery(TITLE, "谢霆风 杭州", "杭州"));
    }
    @Test public void unrelatedLongTokenCannotHideValidExactName() {
        assertEquals("谢霆锋", CatalogKeyword.fromQuery(TITLE, "未在标题出现的描述 谢霆锋 杭州", "杭州"));
    }
    @Test public void resultIsAnActualSubstringForDecisionEngine() {
        String result = CatalogKeyword.fromQuery(TITLE, "evolutionniclive 谢霆锋 杭州", "杭州");
        assertNotNull(result); assertTrue(TITLE.contains(result));
    }
    @Test public void blankAndOversizedInputsAreRejected() {
        assertNull(CatalogKeyword.fromQuery(TITLE, "", "杭州"));
        assertNull(CatalogKeyword.fromQuery(TITLE, "谢".repeat(61), "杭州"));
        assertNull(CatalogKeyword.fromQuery("谢".repeat(501), "谢霆锋", "杭州"));
    }
}
