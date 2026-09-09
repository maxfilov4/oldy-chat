package chat.oldy;
final class Conversation {
 static boolean community(String c){return c.startsWith("room:")||c.startsWith("thread:");}
 static boolean thread(String c){return c.startsWith("thread:");}
 static String room(String c){return c.startsWith("thread:")?c.split(":")[1]:c.substring(5);}
 static String post(String c){return c.startsWith("thread:")?c.split(":")[2]:"";}
}
