package com.allam.journal;
public final class Xml {
 private Xml() {}
 public static String escape(Object value) {
  if(value==null) return "";
  StringBuilder out=new StringBuilder();value.toString().codePoints().filter(c->c==9 || c==10 || c==13 || c>=32 && c<=0xD7FF || c>=0xE000 && c<=0xFFFD || c>=0x10000 && c<=0x10FFFF).forEach(out::appendCodePoint);
  return out.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
 }
}
