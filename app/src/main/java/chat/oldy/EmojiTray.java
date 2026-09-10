package chat.oldy;
import android.view.*;import android.widget.*;import org.json.*;

final class EmojiTray {
 static final String[] NAMES={"Смайлы","Жесты","Любовь","Игры","Еда","Живые","Мои стикеры"};
 static final String[] ICONS={"☺","👍","♡","🎮","☕","✦","▣"};
 static final String[] FACES={
  "😀 😃 😄 😁 😆 😅 😂 🤣 🥹 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😋 😜 🤪 😎 🥳 🤩 🫠 🫡 🫢 🫣 🫥 🫤 🥲 🥺 😭 😤 🤬 🤯 😳 😨 😱 🤔 🧐 🤓 😴 🥱 🤐 🤒 🤕 🤧",
  "👍 👎 👏 🙌 🫶 🤝 🙏 💪 🦾 👊 ✊ 🤛 🤜 ✌️ 🤞 🫰 🤟 🤘 👌 🤌 🤏 👈 👉 👆 👇 ☝️ 🖐️ ✋ 🫳 🫴 👋 🫱 🫲 👀 👂 🧠",
  "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 💔 ❤️‍🔥 ❤️‍🩹 💕 💞 💓 💗 💖 💘 💝 💟 🫶 🥰 😍 😘 🌹 💐 ✨ 🥹",
  "🎮 🕹️ 👾 🤖 🎲 ♟️ 🧩 🏆 🥇 🎯 🏁 ⚽ 🏀 🎾 🏎️ 🚀 🛸 ⚡ 🔥 💯 💥 💫 🪄 💎 🛡️ ⚔️ 🏹 🧙 🧛 🧟 🐉 👻 💀 😈 👑 🌌 🌃",
  "☕ 🍵 🧋 🥤 🧃 🥛 🍿 🍫 🍪 🍩 🎂 🍰 🧁 🍦 🍕 🍔 🍟 🌭 🥪 🌮 🌯 🍜 🍝 🍣 🍱 🥟 🥗 🥑 🍎 🍌 🍓 🍒 🫐 🍇 🍉 🍊"
 };
 static void show(MainActivity a){if(a.emojiPanel==null||a.compose==null)return;if(a.emojiPanel.getVisibility()==View.VISIBLE){a.emojiPanel.setVisibility(View.GONE);return;}((android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(a.compose.getWindowToken(),0);a.emojiPanel.setVisibility(View.VISIBLE);fill(a,0);}
 static void fill(MainActivity a,int category){if(a.emojiPanel==null)return;a.emojiPanel.removeAllViews();a.emojiPanel.setBackground(a.shape(a.CARD,18));
  LinearLayout head=a.row();TextView label=a.label(I18n.t(NAMES[category]),12,a.MUTED);label.setPadding(a.dp(12),0,0,0);head.addView(label,new LinearLayout.LayoutParams(0,a.dp(30),1));head.addView(a.button("×",false,()->a.emojiPanel.setVisibility(View.GONE)),new LinearLayout.LayoutParams(a.dp(38),a.dp(30)));a.emojiPanel.addView(head);
  LinearLayout tabs=a.row();for(int i=0;i<NAMES.length;i++){final int n=i;TextView tab=a.label(ICONS[i],22,category==i?a.GREEN:a.MUTED);tab.setGravity(Gravity.CENTER);tab.setContentDescription(I18n.t(NAMES[i]));if(i==category)tab.setBackground(a.shape(a.light?0xffd6e9fc:0xff2e3555,12));tab.setOnClickListener(v->{if(n==6)PersonalStickers.show(a);else fill(a,n);} );tabs.addView(tab,new LinearLayout.LayoutParams(0,a.dp(38),1));}a.emojiPanel.addView(tabs);
  if(category==6){PersonalStickers.show(a);return;}
  ScrollView scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(true);GridLayout grid=new GridLayout(a);int columns=Math.max(4,(a.getResources().getDisplayMetrics().widthPixels-a.dp(40))/a.dp(category==6?80:48));grid.setColumnCount(columns);scroll.addView(grid);
  String[] values=category<5?FACES[category].split(" "):MotionEmoji.GLYPHS;int cellHeight=category==6?82:52;
  for(int i=0;i<values.length;i++){final int n=i;View cell;if(category==5)cell=new MotionEmoji(a,i);else{TextView t=a.label(values[i],27,a.TEXT);t.setGravity(Gravity.CENTER);cell=t;}
   GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(i/columns),GridLayout.spec(i%columns,1f));lp.width=0;lp.height=a.dp(cellHeight);grid.addView(cell,lp);
   cell.setOnClickListener(v->{int at=Math.max(0,a.compose.getSelectionStart());a.compose.getText().insert(at,values[n]);if(category==5)a.selectedMotion=n;});
  }
  a.emojiPanel.addView(scroll,new LinearLayout.LayoutParams(-1,a.dp(cellHeight*2)));a.space(a.emojiPanel,4);
 }
}
