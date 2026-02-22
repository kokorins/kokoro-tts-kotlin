package net.alexsobolev.tts.infra.g2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("LargeClass")
class LetterPhonemeConverterTest {
    private val converter = LetterPhonemeConverter(LetterPhonemeRules())

    // ── Null / invalid input ─────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertNull(converter.convert(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(converter.convert("   "))
    }

    @Test
    fun `digits returns null`() {
        assertNull(converter.convert("abc123"))
    }

    @Test
    fun `punctuation returns null`() {
        assertNull(converter.convert("hello!"))
    }

    @Test
    fun `hyphen returns null`() {
        assertNull(converter.convert("well-known"))
    }

    @Test
    fun `apostrophe is allowed`() {
        // don't → d-o-n-'-t
        // d→"d", o(next=n)→"ɑ", n→"n", '→skip, t→"t" = "dɑnt", 1 syllable

        assertNotNull(converter.convert("don't"))
        assertEquals("dˈɑnt", converter.convert("don't"))
    }

    // ── Case insensitivity & whitespace ──────────────────────────

    @Test
    fun `uppercase input`() {
        assertEquals("kˈæt", converter.convert("CAT"))
    }

    @Test
    fun `mixed case input`() {
        assertEquals("kˈæt", converter.convert("CaT"))
    }

    @Test
    fun `leading trailing whitespace`() {
        assertEquals("kˈæt", converter.convert("  cat  "))
    }

    // ── 5-character patterns ─────────────────────────────────────

    @Test
    fun `ought pattern`() {
        // "ought" → "ɔt", 1 syllable

        assertEquals("ˈɔt", converter.convert("ought"))
    }

    @Test
    fun `ought in word`() {
        // "bought" → b→"b" + ought→"ɔt" = "bɔt"

        assertEquals("bˈɔt", converter.convert("bought"))
    }

    @Test
    fun `ation pattern`() {
        // "ation" → "Aʃən", 2 syllables (A, ə), stress penultimate

        assertEquals("ˈAʃən", converter.convert("ation"))
    }

    @Test
    fun `ation in word`() {
        // "nation" → n→"n" + ation→"Aʃən" = "nAʃən", stress penultimate (A)

        assertEquals("nˈAʃən", converter.convert("nation"))
    }

    // ── 4-character patterns ─────────────────────────────────────

    @Test
    fun `tion pattern`() {
        // "tion" → "ʃən", 1 syllable (ə)

        assertEquals("ʃˈən", converter.convert("tion"))
    }

    @Test
    fun `sion pattern`() {
        // "sion" → "ʒən", 1 syllable

        assertEquals("ʒˈən", converter.convert("sion"))
    }

    @Test
    fun `ious pattern`() {
        // "ious" → "iəs", 2 syllables (i, ə), stress penultimate

        assertEquals("ˈiəs", converter.convert("ious"))
    }

    @Test
    fun `eous pattern`() {
        // "eous" → "iəs"

        assertEquals("ˈiəs", converter.convert("eous"))
    }

    @Test
    fun `ight pattern`() {
        // "light" → l→"l" + ight→"It" = "lIt"

        assertEquals("lˈIt", converter.convert("light"))
    }

    @Test
    fun `ight standalone`() {
        assertEquals("ˈIt", converter.convert("ight"))
    }

    @Test
    fun `ough pattern`() {
        // "ough" → "O" (as in "though"), 1 syllable

        assertEquals("ˈO", converter.convert("ough"))
    }

    @Test
    fun `ture pattern`() {
        // "ture" → "ʧəɹ", 1 syllable (ə)

        assertEquals("ʧˈəɹ", converter.convert("ture"))
    }

    @Test
    fun `sure pattern`() {
        // "sure" → matched as 4-char pattern → "ʒəɹ"

        assertEquals("ʒˈəɹ", converter.convert("sure"))
    }

    @Test
    fun `tial pattern`() {
        assertEquals("ʃˈəl", converter.convert("tial"))
    }

    @Test
    fun `cial pattern`() {
        assertEquals("ʃˈəl", converter.convert("cial"))
    }

    // ── 3-character consonant patterns ───────────────────────────

    @Test
    fun `tch in catch`() {
        // "catch" → k→"k", a→"æ", tch→"ʧ" = "kæʧ"

        assertEquals("kˈæʧ", converter.convert("catch"))
    }

    @Test
    fun `dge in badge`() {
        // "badge" → b→"b", a→"æ", dge→"ʤ" = "bæʤ"

        assertEquals("bˈæʤ", converter.convert("badge"))
    }

    @Test
    fun `sch in school`() {
        // "school" → sch→"sk", oo→"u", l→"l" = "skul"

        assertEquals("skˈul", converter.convert("school"))
    }

    @Test
    fun `scr in scrap`() {
        // "scrap" → scr→"skɹ", a→"æ", p→"p" = "skɹæp"

        assertEquals("skɹˈæp", converter.convert("scrap"))
    }

    @Test
    fun `shr in shrug`() {
        // "shrug" → shr→"ʃɹ", u(prev=r∈djlnrst)→"u", g(end)→"ɡ" = "ʃɹuɡ"

        assertEquals("ʃɹˈuɡ", converter.convert("shrug"))
    }

    @Test
    fun `str in strap`() {
        // "strap" → str→"stɹ", a→"æ", p→"p" = "stɹæp"

        assertEquals("stɹˈæp", converter.convert("strap"))
    }

    @Test
    fun `thr in throw`() {
        // "throw" → thr→"θɹ", ow(at end)→"W" = "θɹW"

        assertEquals("θɹˈW", converter.convert("throw"))
    }

    @Test
    fun `chr in chrome`() {
        // "chrome" → chr→"kɹ", o+m+e(silent-e)→"Om" = "kɹOm"

        assertEquals("kɹˈOm", converter.convert("chrome"))
    }

    // ── 3-character vowel+r patterns ─────────────────────────────

    @Test
    fun `air in fair`() {
        // "fair" → f→"f", air→"ɛɹ" = "fɛɹ"

        assertEquals("fˈɛɹ", converter.convert("fair"))
    }

    @Test
    fun `ear in fear`() {
        // "fear" → f→"f", ear→"iɹ" = "fiɹ"

        assertEquals("fˈiɹ", converter.convert("fear"))
    }

    @Test
    fun `eer in beer`() {
        // "beer" → b→"b", eer→"iɹ" = "biɹ"

        assertEquals("bˈiɹ", converter.convert("beer"))
    }

    @Test
    fun `our standalone`() {
        // "our" → our→"Wɹ"

        assertEquals("ˈWɹ", converter.convert("our"))
    }

    @Test
    fun `oor in floor`() {
        // "floor" → f→"f", l→"l", oor→"ɔɹ" = "flɔɹ"

        assertEquals("flˈɔɹ", converter.convert("floor"))
    }

    @Test
    fun `ore in more`() {
        // "more" → m→"m", ore→"ɔɹ" = "mɔɹ"

        assertEquals("mˈɔɹ", converter.convert("more"))
    }

    @Test
    fun `ire in fire`() {
        // "fire" → f→"f", ire→"Iɹ" = "fIɹ"

        assertEquals("fˈIɹ", converter.convert("fire"))
    }

    @Test
    fun `ure in pure`() {
        // "pure" → p→"p", ure→"jʊɹ" = "pjʊɹ", stress on ʊ

        assertEquals("pjˈʊɹ", converter.convert("pure"))
    }

    @Test
    fun `are at word end`() {
        // "bare" → b→"b", are(at end, pos+3=4=len)→"ɛɹ" = "bɛɹ"

        assertEquals("bˈɛɹ", converter.convert("bare"))
    }

    @Test
    fun `are not at word end`() {
        // "area" → a(next=r)→"ɑ", r→"ɹ", ea→"i" = "ɑɹi", 2 syllables

        assertEquals("ˈɑɹi", converter.convert("area"))
    }

    // ── 3-character suffix patterns ──────────────────────────────

    @Test
    fun `ous standalone`() {
        assertEquals("ˈəs", converter.convert("ous"))
    }

    @Test
    fun `ess in less`() {
        // "less" → l→"l", ess→"ɛs" = "lɛs"

        assertEquals("lˈɛs", converter.convert("less"))
    }

    @Test
    fun `ing in sing`() {
        // "sing" → s→"s", ing→"ɪŋ" = "sɪŋ"

        assertEquals("sˈɪŋ", converter.convert("sing"))
    }

    @Test
    fun `ble in able`() {
        // "able" → a(next=b)→"æ", ble→"bəl" = "æbəl", 2 syllables

        assertEquals("ˈæbəl", converter.convert("able"))
    }

    @Test
    fun `ple standalone`() {
        assertEquals("pˈəl", converter.convert("ple"))
    }

    @Test
    fun `tle standalone`() {
        assertEquals("tˈəl", converter.convert("tle"))
    }

    @Test
    fun `dle standalone`() {
        assertEquals("dˈəl", converter.convert("dle"))
    }

    @Test
    fun `gle standalone`() {
        assertEquals("ɡˈəl", converter.convert("gle"))
    }

    @Test
    fun `kle standalone`() {
        assertEquals("kˈəl", converter.convert("kle"))
    }

    @Test
    fun `ful standalone`() {
        assertEquals("fˈəl", converter.convert("ful"))
    }

    @Test
    fun `all standalone`() {
        assertEquals("ˈɔl", converter.convert("all"))
    }

    @Test
    fun `alk in walk`() {
        // "walk" → w→"w", alk→"ɔk" = "wɔk"

        assertEquals("wˈɔk", converter.convert("walk"))
    }

    @Test
    fun `ism standalone`() {
        // "ism" → "ɪzəm", 2 syllables

        assertEquals("ˈɪzəm", converter.convert("ism"))
    }

    @Test
    fun `ist in fist`() {
        // "fist" → f→"f", ist→"ɪst" = "fɪst"

        assertEquals("fˈɪst", converter.convert("fist"))
    }

    @Test
    fun `ity standalone`() {
        // "ity" → "ɪti", 2 syllables

        assertEquals("ˈɪti", converter.convert("ity"))
    }

    @Test
    fun `ily standalone`() {
        // "ily" → "ɪli", 2 syllables

        assertEquals("ˈɪli", converter.convert("ily"))
    }

    @Test
    fun `age at word end`() {
        // "age" → age(at end, pos+3=3=len)→"ɪʤ"

        assertEquals("ˈɪʤ", converter.convert("age"))
    }

    @Test
    fun `ive at word end`() {
        // "live" → l→"l", ive(at end)→"ɪv" = "lɪv"

        assertEquals("lˈɪv", converter.convert("live"))
    }

    @Test
    fun `ize standalone`() {
        assertEquals("ˈIz", converter.convert("ize"))
    }

    @Test
    fun `ise standalone`() {
        assertEquals("ˈIz", converter.convert("ise"))
    }

    @Test
    fun `ary standalone`() {
        // "ary" → "ɛɹi", 2 syllables

        assertEquals("ˈɛɹi", converter.convert("ary"))
    }

    @Test
    fun `ery standalone`() {
        assertEquals("ˈɛɹi", converter.convert("ery"))
    }

    @Test
    fun `ory standalone`() {
        // "ory" → "ɔɹi", 2 syllables

        assertEquals("ˈɔɹi", converter.convert("ory"))
    }

    @Test
    fun `ate at end long word`() {
        // "generate" → g(next=e∈eiy)→"ʤ", e→"ɛ", n→"n", e(next=r)→"ə", r→"ɹ",
        //   ate(at end, len=8>4)→"At" = "ʤɛnəɹAt", 3 syllables, stress penultimate (ə)

        assertEquals("ʤɛnˈəɹAt", converter.convert("generate"))
    }

    @Test
    fun `ate at end short word falls to silent e`() {
        // "gate" (4 chars, not > 4) → g(next=a)→"ɡ", a+t+e(silent-e)→"At" = "ɡAt"

        assertEquals("ɡˈAt", converter.convert("gate"))
    }

    // ── 2-character consonant digraphs ───────────────────────────

    @Test
    fun `th voiced at start before vowel`() {
        // "that" → th(start, next=a∈aeiouy)→"ð", a→"æ", t→"t" = "ðæt"

        assertEquals("ðˈæt", converter.convert("that"))
    }

    @Test
    fun `th voiceless not at start`() {
        // "math" → m→"m", a→"æ", th(not start)→"θ" = "mæθ"

        assertEquals("mˈæθ", converter.convert("math"))
    }

    @Test
    fun `th voiceless at start before consonant`() {
        // "three" → thr(3-char pattern)→"θɹ", ee→"i" = "θɹi"

        assertEquals("θɹˈi", converter.convert("three"))
    }

    @Test
    fun `sh pattern`() {
        // "ship" → sh→"ʃ", i→"ɪ", p→"p" = "ʃɪp"

        assertEquals("ʃˈɪp", converter.convert("ship"))
    }

    @Test
    fun `ch pattern`() {
        // "chip" → ch→"ʧ", i→"ɪ", p→"p" = "ʧɪp"

        assertEquals("ʧˈɪp", converter.convert("chip"))
    }

    @Test
    fun `ph pattern`() {
        // "phone" → ph→"f", o+n+e(silent-e)→"On" = "fOn"

        assertEquals("fˈOn", converter.convert("phone"))
    }

    @Test
    fun `wh pattern`() {
        // "when" → wh→"w", e→"ɛ", n→"n" = "wɛn"

        assertEquals("wˈɛn", converter.convert("when"))
    }

    @Test
    fun `wr pattern`() {
        // "wrap" → wr→"ɹ", a→"æ", p→"p" = "ɹæp"

        assertEquals("ɹˈæp", converter.convert("wrap"))
    }

    @Test
    fun `kn pattern`() {
        // "knot" → kn→"n", o(next=t)→"ɑ", t→"t" = "nɑt"

        assertEquals("nˈɑt", converter.convert("knot"))
    }

    @Test
    fun `gn at word start`() {
        // "gnat" → gn(start)→"n", a→"æ", t→"t" = "næt"

        assertEquals("nˈæt", converter.convert("gnat"))
    }

    @Test
    fun `gn not at word start`() {
        // "sign" → s→"s", i→"ɪ", g(next=n∉eiy)→"ɡ", n→"n" = "sɪɡn"

        assertEquals("sˈɪɡn", converter.convert("sign"))
    }

    @Test
    fun `gh at word start`() {
        // "ghost" → gh(start)→"ɡ", o(next=s)→"ɑ", s→"s", t→"t" = "ɡɑst"

        assertEquals("ɡˈɑst", converter.convert("ghost"))
    }

    @Test
    fun `gh silent in middle`() {
        // "sigh" → s→"s", i→"ɪ", gh(not start)→silent = "sɪ"

        assertEquals("sˈɪ", converter.convert("sigh"))
    }

    @Test
    fun `ck pattern`() {
        // "back" → b→"b", a→"æ", ck→"k" = "bæk"

        assertEquals("bˈæk", converter.convert("back"))
    }

    @Test
    fun `ng pattern`() {
        // "song" → s→"s", o(next=n)→"ɑ", ng→"ŋ" = "sɑŋ"

        assertEquals("sˈɑŋ", converter.convert("song"))
    }

    @Test
    fun `nk pattern`() {
        // "bank" → b→"b", a(next=n)→"æ", nk→"ŋk" = "bæŋk"

        assertEquals("bˈæŋk", converter.convert("bank"))
    }

    @Test
    fun `qu pattern`() {
        // "quit" → qu→"kw", i→"ɪ", t→"t" = "kwɪt"

        assertEquals("kwˈɪt", converter.convert("quit"))
    }

    // ── Vowel digraphs ───────────────────────────────────────────

    @Test
    fun `ee digraph`() {
        // "feed" → f→"f", ee→"i", d→"d" = "fid"

        assertEquals("fˈid", converter.convert("feed"))
    }

    @Test
    fun `ea digraph`() {
        // "read" → r→"ɹ", ea→"i", d→"d" = "ɹid"

        assertEquals("ɹˈid", converter.convert("read"))
    }

    @Test
    fun `ai digraph`() {
        // "rain" → r→"ɹ", ai→"A", n→"n" = "ɹAn"

        assertEquals("ɹˈAn", converter.convert("rain"))
    }

    @Test
    fun `ay digraph`() {
        // "day" → d→"d", ay→"A" = "dA"

        assertEquals("dˈA", converter.convert("day"))
    }

    @Test
    fun `oa digraph`() {
        // "boat" → b→"b", oa→"O", t→"t" = "bOt"

        assertEquals("bˈOt", converter.convert("boat"))
    }

    @Test
    fun `ow at word end`() {
        // "cow" → c(next=o)→"k", ow(at end)→"W" = "kW"

        assertEquals("kˈW", converter.convert("cow"))
    }

    @Test
    fun `ow before n`() {
        // "town" → t→"t", ow(next=n∈{n,l,e})→"W", n→"n" = "tWn"

        assertEquals("tˈWn", converter.convert("town"))
    }

    @Test
    fun `ow before l`() {
        // "owl" → ow(next=l∈{n,l,e})→"W", l→"l" = "Wl"

        assertEquals("ˈWl", converter.convert("owl"))
    }

    @Test
    fun `ow elsewhere`() {
        // "rowing" → r→"ɹ", ow(next=i∉{n,l,e}, not end)→"O", ing→"ɪŋ" = "ɹOɪŋ"
        // O and ɪ are adjacent vowels → counted as 1 syllable cluster

        assertEquals("ɹˈOɪŋ", converter.convert("rowing"))
    }

    @Test
    fun `ou digraph`() {
        // "loud" → l→"l", ou→"W", d→"d" = "lWd"

        assertEquals("lˈWd", converter.convert("loud"))
    }

    @Test
    fun `oo digraph`() {
        // "food" → f→"f", oo→"u", d→"d" = "fud"

        assertEquals("fˈud", converter.convert("food"))
    }

    @Test
    fun `oi digraph`() {
        // "coin" → c(next=o)→"k", oi→"Y", n→"n" = "kYn"

        assertEquals("kˈYn", converter.convert("coin"))
    }

    @Test
    fun `oy digraph`() {
        // "boy" → b→"b", oy→"Y" = "bY"

        assertEquals("bˈY", converter.convert("boy"))
    }

    @Test
    fun `ie at word end`() {
        // "pie" → p→"p", ie(at end)→"i" = "pi"

        assertEquals("pˈi", converter.convert("pie"))
    }

    @Test
    fun `ie in middle`() {
        // "field" → f→"f", ie→"i", l→"l", d→"d" = "fild"

        assertEquals("fˈild", converter.convert("field"))
    }

    @Test
    fun `ey at word end`() {
        // "key" → k→"k", ey(at end)→"i" = "ki"

        assertEquals("kˈi", converter.convert("key"))
    }

    @Test
    fun `aw digraph`() {
        // "law" → l→"l", aw→"ɔ" = "lɔ"

        assertEquals("lˈɔ", converter.convert("law"))
    }

    @Test
    fun `au digraph`() {
        // "haul" → h→"h", au→"ɔ", l→"l" = "hɔl"

        assertEquals("hˈɔl", converter.convert("haul"))
    }

    @Test
    fun `ew digraph`() {
        // "new" → n→"n", ew→"ju" = "nju", stress on u

        assertEquals("njˈu", converter.convert("new"))
    }

    // ── Doubled consonants ───────────────────────────────────────

    @Test
    fun `doubled consonant collapsed`() {
        // "bell" → b→"b", e→"ɛ", ll→"l"(consumed 2) = "bɛl"

        assertEquals("bˈɛl", converter.convert("bell"))
    }

    @Test
    fun `doubled consonant in middle`() {
        // "butter" → b→"b", u(prev=b∉djlnrst)→"ʌ", tt→"t"(consumed 2),
        //   e(next=r)→"ə", r→"ɹ" = "bʌtəɹ", 2 syllables

        assertEquals("bˈʌtəɹ", converter.convert("butter"))
    }

    @Test
    fun `doubled ss`() {
        // "miss" → m→"m", i→"ɪ", ss→"s"(consumed 2) = "mɪs"

        assertEquals("mˈɪs", converter.convert("miss"))
    }

    // ── Vowel + consonant + silent e ─────────────────────────────

    @Test
    fun `silent e with a`() {
        // "bake" → b→"b", a+k+e→"Ak" = "bAk"

        assertEquals("bˈAk", converter.convert("bake"))
    }

    @Test
    fun `silent e with e`() {
        // "theme" → th(start,next=e∈aeiouy)→"ð", e+m+e→"im" = "ðim"

        assertEquals("ðˈim", converter.convert("theme"))
    }

    @Test
    fun `silent e with i`() {
        // "bike" → b→"b", i+k+e→"Ik" = "bIk"

        assertEquals("bˈIk", converter.convert("bike"))
    }

    @Test
    fun `silent e with o`() {
        // "home" → h→"h", o+m+e→"Om" = "hOm"

        assertEquals("hˈOm", converter.convert("home"))
    }

    @Test
    fun `silent e with u`() {
        // "cute" → c(next=u∉eiy)→"k", u+t+e→"jut" = "kjut", stress on u

        assertEquals("kjˈut", converter.convert("cute"))
    }

    @Test
    fun `silent e not triggered with consonant r`() {
        // "care" → consonant is 'r', so silent-e pattern skipped.
        // c(next=a∉eiy)→"k", are(at end)→"ɛɹ" = "kɛɹ"

        assertEquals("kˈɛɹ", converter.convert("care"))
    }

    // ── Single vowel 'a' ─────────────────────────────────────────

    @Test
    fun `a before r`() {
        // "bar" → b→"b", a(next=r)→"ɑ", r→"ɹ" = "bɑɹ"

        assertEquals("bˈɑɹ", converter.convert("bar"))
    }

    @Test
    fun `a before l plus consonant`() {
        // "salt" → s→"s", a(next=l, pos+2='t'∉aeiou)→"ɔ", l→"l", t→"t" = "sɔlt"

        assertEquals("sˈɔlt", converter.convert("salt"))
    }

    @Test
    fun `a default`() {
        // "cat" → c(next=a)→"k", a(next=t)→"æ", t→"t" = "kæt"

        assertEquals("kˈæt", converter.convert("cat"))
    }

    // ── Single vowel 'e' ─────────────────────────────────────────

    @Test
    fun `e silent at word end`() {
        // "false" → f→"f", a(next=l,pos+2=s∉aeiou)→"ɔ", l→"l", s→"s", e(end,len>2)→null

        assertEquals("fˈɔls", converter.convert("false"))
    }

    @Test
    fun `e at end short word`() {
        // "me" (len=2, not >2) → m→"m", e(end)→"i" = "mi"

        assertEquals("mˈi", converter.convert("me"))
    }

    @Test
    fun `e before r`() {
        // "her" → h→"h", e(next=r)→"ə", r→"ɹ" = "həɹ"

        assertEquals("hˈəɹ", converter.convert("her"))
    }

    @Test
    fun `e default`() {
        // "bed" → b→"b", e(next=d)→"ɛ", d→"d" = "bɛd"

        assertEquals("bˈɛd", converter.convert("bed"))
    }

    // ── Single vowel 'i' ─────────────────────────────────────────

    @Test
    fun `i default`() {
        // "bit" → b→"b", i→"ɪ", t→"t" = "bɪt"

        assertEquals("bˈɪt", converter.convert("bit"))
    }

    // ── Single vowel 'o' ─────────────────────────────────────────

    @Test
    fun `o before r`() {
        // "for" → f→"f", o(next=r)→"ɔ", r→"ɹ" = "fɔɹ"

        assertEquals("fˈɔɹ", converter.convert("for"))
    }

    @Test
    fun `o at word end`() {
        // "go" → g(next=o∉eiy)→"ɡ", o(end)→"O" = "ɡO"

        assertEquals("ɡˈO", converter.convert("go"))
    }

    @Test
    fun `o default`() {
        // "hot" → h→"h", o(next=t)→"ɑ", t→"t" = "hɑt"

        assertEquals("hˈɑt", converter.convert("hot"))
    }

    // ── Single vowel 'u' ─────────────────────────────────────────

    @Test
    fun `u before r`() {
        // "fur" → f→"f", u(next=r)→"ə", r→"ɹ" = "fəɹ"

        assertEquals("fˈəɹ", converter.convert("fur"))
    }

    @Test
    fun `u after djlnrst`() {
        // "run" → r→"ɹ", u(prev=r∈djlnrst)→"u", n→"n" = "ɹun"

        assertEquals("ɹˈun", converter.convert("run"))
    }

    @Test
    fun `u default`() {
        // "bus" → b→"b", u(prev=b∉djlnrst)→"ʌ", s(end,prev=u∈aeiou)→"z" = "bʌz"

        assertEquals("bˈʌz", converter.convert("bus"))
    }

    // ── Single vowel 'y' ─────────────────────────────────────────

    @Test
    fun `y at word start`() {
        // "yes" → y(pos=0)→"j", e→"ɛ", s(end,prev=e∈aeiou)→"z" = "jɛz"

        assertEquals("jˈɛz", converter.convert("yes"))
    }

    @Test
    fun `y at word end`() {
        // "happy" → h→"h", a→"æ", pp→"p"(consumed 2), y(end)→"i" = "hæpi", 2 syllables

        assertEquals("hˈæpi", converter.convert("happy"))
    }

    @Test
    fun `y before consonant`() {
        // "gym" → g(next=y∈eiy)→"ʤ", y(next=m∉aeiou)→"ɪ", m→"m" = "ʤɪm"

        assertEquals("ʤˈɪm", converter.convert("gym"))
    }

    // ── Context-dependent consonants ─────────────────────────────

    @Test
    fun `c soft before e`() {
        // "cent" → c(next=e∈eiy)→"s", e→"ɛ", n→"n", t→"t" = "sɛnt"

        assertEquals("sˈɛnt", converter.convert("cent"))
    }

    @Test
    fun `c soft before i`() {
        // "city" → c(next=i∈eiy)→"s", ity→"ɪti" = "sɪti", 2 syllables

        assertEquals("sˈɪti", converter.convert("city"))
    }

    @Test
    fun `c hard default`() {
        // "cup" → c(next=u∉eiy)→"k", u(prev=k? no, prev=c...)
        // Wait, singleCharacter is called with ch='c'. It returns "k".
        // Then at pos 1: 'u' → prev=word[0]='c', 'c' not in "djlnrst" → "ʌ"
        // pos 2: 'p' → "p"
        // result: "kʌp"

        assertEquals("kˈʌp", converter.convert("cup"))
    }

    @Test
    fun `g soft before e`() {
        // "gem" → g(next=e∈eiy)→"ʤ", e→"ɛ", m→"m" = "ʤɛm"

        assertEquals("ʤˈɛm", converter.convert("gem"))
    }

    @Test
    fun `g hard default`() {
        // "gap" → g(next=a∉eiy)→"ɡ", a→"æ", p→"p" = "ɡæp"

        assertEquals("ɡˈæp", converter.convert("gap"))
    }

    @Test
    fun `s voiced at end after vowel`() {
        // "has" → h→"h", a→"æ", s(end,prev=a∈aeiou)→"z" = "hæz"

        assertEquals("hˈæz", converter.convert("has"))
    }

    @Test
    fun `s sh before ur`() {
        // "sur" → s(next=u,pos+2=r)→"ʃ", u(next=r)→"ə", r→"ɹ" = "ʃəɹ"

        assertEquals("ʃˈəɹ", converter.convert("sur"))
    }

    @Test
    fun `s default`() {
        // "sit" → s→"s", i→"ɪ", t→"t" = "sɪt"

        assertEquals("sˈɪt", converter.convert("sit"))
    }

    @Test
    fun `x at word start`() {
        // "xen" → x(pos=0)→"z", e→"ɛ", n→"n" = "zɛn"

        assertEquals("zˈɛn", converter.convert("xen"))
    }

    @Test
    fun `x elsewhere`() {
        // "box" → b→"b", o(next=x)→"ɑ", x→"ks" = "bɑks"

        assertEquals("bˈɑks", converter.convert("box"))
    }

    @Test
    fun `j consonant`() {
        // "jet" → j→"ʤ", e→"ɛ", t→"t" = "ʤɛt"

        assertEquals("ʤˈɛt", converter.convert("jet"))
    }

    // ── Silent h after certain consonants ────────────────────────

    @Test
    fun `h silent after r`() {
        // "rhyme" → r→"ɹ", h(prev=r∈tcsgrw)→null, y(next=m∉aeiou)→"ɪ",
        //   m→"m", e(end,len>2)→null = "ɹɪm"

        assertEquals("ɹˈɪm", converter.convert("rhyme"))
    }

    @Test
    fun `h not silent`() {
        // "hat" → h(prev=null)→"h", a→"æ", t→"t" = "hæt"

        assertEquals("hˈæt", converter.convert("hat"))
    }

    // ── Stress placement ─────────────────────────────────────────

    @Test
    fun `monosyllabic stress`() {
        // Stress placed at beginning of single vowel cluster

        assertEquals("kˈæt", converter.convert("cat"))
    }

    @Test
    fun `bisyllabic stress penultimate`() {
        // 2 syllables → stress on first (penultimate)

        assertEquals("hˈæpi", converter.convert("happy"))
    }

    @Test
    fun `trisyllabic stress penultimate`() {
        // 3 syllables → stress on second (penultimate)

        assertEquals("ʤɛnˈəɹAt", converter.convert("generate"))
    }

    @Test
    fun `no vowels returns unstressed`() {
        // "nth" → n→"n", th(not start)→"θ" = "nθ", no vowels → no stress marker

        assertEquals("nθ", converter.convert("nth"))
    }

    // ── Integration: multi-pattern words ─────────────────────────

    @Test
    fun `teaching`() {
        // "teaching" → t→"t", ea→"i", ch→"ʧ", ing→"ɪŋ" = "tiʧɪŋ", 2 syllables

        assertEquals("tˈiʧɪŋ", converter.convert("teaching"))
    }

    @Test
    fun `nightlight`() {
        // "nightlight" → n→"n", ight→"It", l→"l", ight→"It" = "nItlIt", 2 syllables

        assertEquals("nˈItlIt", converter.convert("nightlight"))
    }

    @Test
    fun `sunshine`() {
        // "sunshine" → s→"s", u(prev=s∈djlnrst)→"u", n→"n",
        //   sh→"ʃ", i+n+e(silent-e)→"In" = "sunʃIn", 2 syllables

        assertEquals("sˈunʃIn", converter.convert("sunshine"))
    }

    @Test
    fun `checkout`() {
        // "checkout" → ch→"ʧ", e→"ɛ", ck→"k", ou→"W", t→"t" = "ʧɛkWt", 2 syllables

        assertEquals("ʧˈɛkWt", converter.convert("checkout"))
    }

    @Test
    fun `blackbird`() {
        // "blackbird" → b→"b", l→"l", a(next=c)→"æ", ck→"k",
        //   b→"b", ir: i→"ɪ", r→"ɹ", d→"d" = "blækbɪɹd", 2 syllables

        assertEquals("blˈækbɪɹd", converter.convert("blackbird"))
    }

    // ── Word-end 4-char conditional patterns (WORD_END_4) ──────────

    @Test
    fun `word end 4 ages`() {
        // "pages" → p→"p", ages(at word end)→"ᵻʤᵻz" = "pᵻʤᵻz", 2 syllables

        assertEquals("pˈᵻʤᵻz", converter.convert("pages"))
    }

    @Test
    fun `word end 4 ares`() {
        // "stares" → s→"s", t→"t", ares(at word end)→"ɛɹz" = "stɛɹz"

        assertEquals("stˈɛɹz", converter.convert("stares"))
    }

    @Test
    fun `word end 4 ives`() {
        // "dives" → d→"d", ives(at word end)→"ɪvz" = "dɪvz"

        assertEquals("dˈɪvz", converter.convert("dives"))
    }

    @Test
    fun `word end 4 ence`() {
        // "fence" → f→"f", ence(at word end)→"əns" = "fəns"

        assertEquals("fˈəns", converter.convert("fence"))
    }

    @Test
    fun `word end 4 ance`() {
        // "dance" → d→"d", ance(at word end)→"əns" = "dəns"

        assertEquals("dˈəns", converter.convert("dance"))
    }

    // ── Sibilant -es ending (ePhoneme path) ────────────────────────

    @Test
    fun `sibilant es after c`() {
        // "faces" → f→"f", a→"æ", c(soft)→"s", e(-es after sibilant 'c')→"ᵻ", s→"z"

        assertEquals("fˈæsᵻz", converter.convert("faces"))
    }

    @Test
    fun `sibilant es after x`() {
        // "boxes" → b→"b", o→"ɑ", x→"ks", e(-es after sibilant 'x')→"ᵻ", s→"z"

        assertEquals("bˈɑksᵻz", converter.convert("boxes"))
    }

    @Test
    fun `sibilant es after z`() {
        // "gazes" → g→"ɡ", a→"æ", z→"z", e(-es after sibilant 'z')→"ᵻ", s→"z"

        assertEquals("ɡˈæzᵻz", converter.convert("gazes"))
    }

    @Test
    fun `non sibilant es gives regular e`() {
        // "makes" → m→"m", a→"æ", k→"k", e(-es after 'k', not sibilant)→"ɛ", s→"z"

        assertEquals("mˈækɛz", converter.convert("makes"))
    }

    // ── y as consonant before vowel in middle ──────────────────────

    @Test
    fun `y before vowel in middle`() {
        // "beyond" → b→"b", e→"ɛ", y(mid, next=o∈aeiou)→"j", o→"ɑ", n→"n", d→"d"

        assertEquals("bˈɛjɑnd", converter.convert("beyond"))
    }

    // ── ow conditional digraph before 'e' ──────────────────────────

    @Test
    fun `ow before e`() {
        // "power" → p→"p", ow(next at +2='e'∈{n,l,e})→"W", e(next=r)→"ə", r→"ɹ"

        assertEquals("pˈWəɹ", converter.convert("power"))
    }

    // ── th voiced in common words ──────────────────────────────────

    @Test
    fun `th voiced in this`() {
        // "this" → th(start, next='i'∈aeiouy)→"ð", i→"ɪ", s(end, prev vowel)→"z"

        assertEquals("ðˈɪz", converter.convert("this"))
    }

    // ── Single-letter word ─────────────────────────────────────────

    @Test
    fun `single letter a`() {
        assertEquals("ˈæ", converter.convert("a"))
    }

    // ── Stress placement: 4+ syllables ─────────────────────────────

    @Test
    fun `four syllable stress penultimate`() {
        // "generation" → ʤ+ɛ+n+ə+ɹ+Aʃən = "ʤɛnəɹAʃən", 4 syllables (ɛ, ə, A, ə)
        // Stress on penultimate (A, the 3rd syllable)

        assertEquals("ʤɛnəɹˈAʃən", converter.convert("generation"))
    }

    // ── Silent gh in middle of word ────────────────────────────────

    @Test
    fun `gh silent in laughing`() {
        // "laughing" → l→"l", au→"ɔ", gh(mid, silent)→"", ing→"ɪŋ" = "lɔɪŋ"

        assertEquals("lˈɔɪŋ", converter.convert("laughing"))
    }

    // ── Silent gh in middle of word ───────────────────────────────

    @Test
    fun `gh silent in daughter`() {
        // "daughter" → d→"d", au→"ɔ", gh(mid, silent)→"", t→"t", e(next=r)→"ə", r→"ɹ"

        assertEquals("dˈɔtəɹ", converter.convert("daughter"))
    }

    // ── Integration: longer multi-pattern words ────────────────────

    @Test
    fun `thoughtful`() {
        // "thoughtful" → th(start, next=o∈aeiouy)→"ð", ought→"ɔt", ful→"fəl" = "ðɔtfəl"

        assertEquals("ðˈɔtfəl", converter.convert("thoughtful"))
    }

    @Test
    fun `nightfall`() {
        // "nightfall" → n→"n", ight→"It", f→"f", all→"ɔl" = "nItfɔl"

        assertEquals("nˈItfɔl", converter.convert("nightfall"))
    }

    @Test
    fun `playground`() {
        // "playground" → p→"p", l→"l", ay→"A", g→"ɡ", r→"ɹ", ou→"W", n→"n", d→"d"

        assertEquals("plˈAɡɹWnd", converter.convert("playground"))
    }

    @Test
    fun `queen`() {
        // "queen" → qu→"kw", ee→"i", n→"n" = "kwin"

        assertEquals("kwˈin", converter.convert("queen"))
    }

    // ── Abbreviation: pure letter ────────────────────────────────

    @Test
    fun `abbreviation TTS`() {
        assertEquals("tˌitˌiˈɛs", converter.convertAbbreviation("TTS"))
    }

    @Test
    fun `abbreviation API`() {
        assertEquals("ˌApˌiˈI", converter.convertAbbreviation("API"))
    }

    @Test
    fun `abbreviation CPU`() {
        assertEquals("sˌipˌijˈu", converter.convertAbbreviation("CPU"))
    }

    // ── Abbreviation: mixed letter + digit ───────────────────────

    @Test
    fun `abbreviation MP3`() {
        assertEquals("ˌɛmpˈi θɹˈi", converter.convertAbbreviation("MP3"))
    }

    @Test
    fun `abbreviation H2O`() {
        assertEquals("ˈAʧ tˈu ˈO", converter.convertAbbreviation("H2O"))
    }

    // ── Abbreviation: two-letter ─────────────────────────────────

    @Test
    fun `abbreviation AI`() {
        assertEquals("ˌAˈI", converter.convertAbbreviation("AI"))
    }

    @Test
    fun `abbreviation OK`() {
        assertEquals("ˌOkˈA", converter.convertAbbreviation("OK"))
    }

    // ── Abbreviation: single digit group ─────────────────────────

    @Test
    fun `abbreviation G7`() {
        // Only 1 uppercase letter — does not meet the ≥ 2 uppercase threshold

        assertNull(converter.convertAbbreviation("G7"))
    }

    // ── Abbreviation: rejection cases ────────────────────────────

    @Test
    fun `abbreviation rejects single letter`() {
        assertNull(converter.convertAbbreviation("A"))
    }

    @Test
    fun `abbreviation rejects lowercase`() {
        assertNull(converter.convertAbbreviation("tts"))
    }

    @Test
    fun `abbreviation rejects mixed case`() {
        assertNull(converter.convertAbbreviation("Tts"))
    }

    @Test
    fun `abbreviation rejects single uppercase plus digit`() {
        assertNull(converter.convertAbbreviation("A1"))
    }
}
