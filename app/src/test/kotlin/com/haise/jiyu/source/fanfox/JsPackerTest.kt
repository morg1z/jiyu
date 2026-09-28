package com.haise.jiyu.source.fanfox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPackerTest {

    // Skutečná odpověď z fanfox.net/manga/ao_ashi/c410/chapterfun.ashx (zachyceno živě).
    private val realEvalResponse = """eval(function(p,a,c,k,e,d){e=function(c){return(c<a?"":e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--)d[e(c)]=k[c]||e(c);k=[function(e){return d[e]}];e=function(){return'\w+'};c=1;};while(c--)if(k[c])p=p.replace(new RegExp('\b'+e(c)+'\b','g'),k[c]);return p;}('k e(){2 f="//8.b.7/c/3/4/6.0/g";2 1=["/n.h?5=m&9=a","/l.h?5=j&9=a"];o(2 i=0;i<1.u;i++){s(i==0){1[i]="//8.b.7/c/3/4/6.0/g"+1[i];p}1[i]=f+1[i]}q 1}2 d;d=e();r=t;',31,31,'|pvalue|var|manga|29225|token|410|me|zjcdn|ttl|1786291200|mangafox|store||dm5imagefun|pix|compressed|jpg||7121d221352c2e762de1bb012f4e6490f05f70fb|function|b20250623_93556_350|3574dcc3740215b971ee7f6545bac9cc1f75285f|b20250623_93556_349|for|continue|return|currentimageid|if|40804494|length'.split('|'),0,{}))"""

    @Test
    fun `unpack decodes real fanfox chapterfun response into readable JS`() {
        val decoded = JsPacker.unpackEval(realEvalResponse)
        assertTrue("expected non-null decode result", decoded != null)
        assertTrue("expected pvalue array", decoded!!.contains("pvalue"))
        assertTrue("expected function keyword substituted", decoded.contains("function dm5imagefun"))
        assertTrue("expected the mangafox cdn host", decoded.contains("zjcdn.mangafox.me"))
        assertTrue("expected first image query token", decoded.contains("b20250623_93556_350"))
        assertTrue("expected second image query token", decoded.contains("b20250623_93556_349"))
    }

    // Skutečná odpověď z mangahome.com chapterfun.ashx (zachyceno na zařízení, 658 B).
    // Regresní test za bug: regex unpackEval začínal literální '}', což na Androidu
    // (ICU regex) háže PatternSyntaxException - JDK ji bere jako literál, takže JVM
    // testy procházely, ale na telefonu se vracela virtuální URL -> černá čtečka.
    private val mangahomeEvalResponse = """eval(function(p,a,c,k,e,d){e=function(c){return(c<a?"":e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--){d[c.toString(a)]=k[c]||c.toString(a)}k=[function(e){return d[e]}];e=function(){return'\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\b'+e(c)+'\b','g'),k[c])}}return p}('g a(){2 c="//5.8.7/6/9/4/b.0/3";2 1=["/k.e","/l.e"];f(2 i=0;i<1.j;i++){h(i==0){1[i]="//5.8.7/6/9/4/b.0/3"+1[i];o}1[i]=c+1[i]}n 1}2 d;d=a();m=p;',26,26,'|pvalue|var|compressed|17871|zjcdn|store|org|mangahere|manga|dm5imagefun|001|pix||jpg|for|function|if||length|m001|m002|currentimageid|return|continue|8071213'.split('|'),0,{}))"""

    @Test
    fun `unpackEval decodes real mangahome chapterfun payload to cdn urls`() {
        val decoded = JsPacker.unpackEval(mangahomeEvalResponse)
        assertTrue("expected non-null decode result", decoded != null)
        assertTrue("expected mangahere cdn host", decoded!!.contains("zjcdn.mangahere.org"))
        assertTrue("expected decoded page file names", decoded.contains("/m001.jpg"))
        assertTrue(decoded.contains("/m002.jpg"))
    }

    @Test
    fun `unpack is a straightforward roundtrip on a minimal synthetic example`() {
        // 'ab' encoded where a=0->"x", b=1->"y" (radix 2, count 2)
        val decoded = JsPacker.unpack("0 1", radix = 2, count = 2, words = listOf("x", "y"))
        assertEquals("x y", decoded)
    }
}
