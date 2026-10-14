package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.messenger.semantic.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import java.util.*;

/** Opt-in downloaded-text search and model controls. */
public class SemanticSearchActivity extends BaseFragment {
    private SemanticSearch search;
    private TextView modelStatus, indexStatus;
    private LinearLayout results;
    private int queryGeneration;
    private boolean visible;
    private long lastRevision=-1;
    @Override public View createView(Context context) {
        search = SemanticSearch.get(currentAccount);
        actionBar.setTitle("Semantic search"); actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() { @Override public void onItemClick(int id) { if (id == -1) finishFragment(); } });
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(16); body.setPadding(padding,padding,padding,padding);
        body.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.addView(body);
        fragmentView = scroll;
        text(body,"Find downloaded messages by meaning. Processing stays on this device. New chats are excluded until selected. Original Telegram search remains available.",14);
        Switch enabled = new Switch(context); enabled.setText("Enable semantic search"); enabled.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); enabled.setChecked(search.enabled()); body.addView(enabled);
        enabled.setOnCheckedChangeListener((button,value) -> { queryGeneration++; results.removeAllViews(); search.enabled(value); update(); });
        heading(body,"Allowed chats and history");
        button(body,"Choose allowed chats",this::chooseChats);
        button(body,"History range",() -> {
            String[] labels = {"Downloaded text: last 7 days","Downloaded text: last 30 days","Downloaded text: last 90 days","All downloaded text"};
            int[] days = {7,30,90,0};
            new AlertDialog.Builder(context).setTitle("History range").setItems(labels,(dialog,which)->{search.days(days[which]);queryGeneration++;results.removeAllViews();update();}).show();
        });
        text(body,"Older history is not fetched by this feature. Downloaded history can contain gaps. Secret chats, protected content and expiring messages are excluded. Initial index limit: 10,000 text chunks per account.",14);
        heading(body,"Model management"); modelStatus = text(body,"",14);
        button(body,"Download model (165 MB)",() -> {
            if (SemanticModel.ready()) { update(); return; }
            ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.isActiveNetworkMetered()) {
                new AlertDialog.Builder(context).setTitle("Download over mobile or metered data?").setMessage("The model download is about 165 MB. No chat messages are uploaded.").setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->SemanticModel.download()).show();
            } else SemanticModel.download();
        });
        button(body,"Cancel model download",SemanticModel::cancelDownload);
        button(body,"Delete model from device",() -> confirmDelete(false));
        button(body,"Delete model and all search indexes",() -> confirmDelete(true));
        text(body,"The model is shared across accounts. Deleting it removes weights and runtime caches and stops semantic search for every account. Delete model alone keeps chat selections and existing indexes. It will not download again automatically.",14);
        heading(body,"Index downloaded messages"); indexStatus=text(body,"",14);
        Switch charging = new Switch(context); charging.setText("Automatic indexing only while charging"); charging.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); charging.setChecked(search.chargingOnly()); body.addView(charging);
        charging.setOnCheckedChangeListener((b,value)->search.chargingOnly(value));
        button(body,"Index now",() -> { search.index(); update(); });
        button(body,"Pause indexing",() -> { search.pause(); update(); });
        button(body,"Clear this account's search index",() -> new AlertDialog.Builder(context).setTitle("Clear search index?").setMessage("Remove this account's vectors and derived text. Telegram messages and chat selections stay available.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{search.clear();queryGeneration++;results.removeAllViews();}).show());
        heading(body,"Search by meaning");
        EditText query = new EditText(context); query.setHint("What are you looking for?"); query.setSingleLine(true); query.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); query.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)); body.addView(query);
        button(body,"Search downloaded chats",() -> {
            String input=query.getText().toString(); int token=++queryGeneration; results.removeAllViews();text(results,"Searching…",14);
            search.search(input,(found,error)->{
                if(!visible||token!=queryGeneration)return;
                results.removeAllViews();
                if(error!=null){text(results,error,14);return;}
                if(found.isEmpty()){text(results,"No indexed matches. Check selected chats and indexing progress.",14);return;}
                for(SemanticSearch.Result result:found){
                    TextView row=text(results,title(result.dialog)+" • "+new java.text.SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault()).format(new Date(result.date*1000L))+"\n"+result.text,16);
                    row.setPadding(padding,padding,padding,padding);
                    row.setOnClickListener(v->{
                        if(!search.permits(result.dialog)){results.removeAllViews();return;}
                        Bundle args=new Bundle(); if(result.dialog>0)args.putLong("user_id",result.dialog);else args.putLong("chat_id",-result.dialog); args.putInt("message_id",result.id);
                        ChatActivity chat=new ChatActivity(args);chat.setCurrentAccount(currentAccount);presentFragment(chat);
                    });
                }
            });
        });
        results=new LinearLayout(context);results.setOrientation(LinearLayout.VERTICAL);body.addView(results);
        update();return fragmentView;
    }
    private void confirmDelete(boolean indexes){
        new AlertDialog.Builder(getParentActivity()).setTitle(indexes?"Delete model and search indexes?":"Delete model?").setMessage(indexes?"Remove the model and derived search data for all accounts. Telegram messages remain available.":"Remove the model and compiled caches. Existing indexes and chat selections remain. Semantic search stops until you explicitly download it again.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{queryGeneration++;results.removeAllViews();SemanticModel.delete();if(indexes)SemanticSearch.clearAllIndexes();update();}).show();
    }
    private void chooseChats(){
        ArrayList<TLRPC.Dialog> dialogs=new ArrayList<>(); Set<Long> seen=new HashSet<>();
        for(int folder:new int[]{0,1})for(TLRPC.Dialog d:getMessagesController().getDialogs(folder))if(search.eligibleDialog(d.id)&&seen.add(d.id))dialogs.add(d);
        dialogs.sort((a,b)->title(a.id).compareToIgnoreCase(title(b.id)));
        String[] labels=new String[dialogs.size()];boolean[] checked=new boolean[dialogs.size()];Set<Long> selected=search.allowed();
        for(int i=0;i<labels.length;i++){labels[i]=title(dialogs.get(i).id);checked[i]=selected.contains(dialogs.get(i).id);}
        new AlertDialog.Builder(getParentActivity()).setTitle("Allow these chats for local indexing").setMultiChoiceItems(labels,checked,(d,which,value)->{search.allow(dialogs.get(which).id,value);queryGeneration++;results.removeAllViews();update();}).setPositiveButton("Done",(d,w)->update()).show();
    }
    private String title(long dialog){
        if(dialog>0){TLRPC.User u=getMessagesController().getUser(dialog);if(u!=null)return UserObject.getUserName(u);}
        else {TLRPC.Chat c=getMessagesController().getChat(-dialog);if(c!=null)return c.title;}
        return "Chat "+dialog;
    }
    private void update(){if(search!=null&&lastRevision!=search.revision()){lastRevision=search.revision();queryGeneration++;if(results!=null)results.removeAllViews();}if(modelStatus!=null)modelStatus.setText(SemanticModel.status());if(indexStatus!=null)indexStatus.setText(search.status()+"\n"+search.allowed().size()+" chats selected • "+(search.days()==0?"all downloaded history":search.days()+" days"));}
    private TextView text(LinearLayout parent,String value,int size){TextView t=new TextView(parent.getContext());t.setText(value);t.setTextSize(size);t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));t.setPadding(0,AndroidUtilities.dp(8),0,AndroidUtilities.dp(8));parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private void heading(LinearLayout parent,String value){text(parent,value,18);}
    private void button(LinearLayout parent,String title,Runnable action){Button b=new Button(parent.getContext());b.setText(title);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    @Override public void onResume(){super.onResume();visible=true;search.observe(this::update);SemanticModel.observe(this::update);search.autoIndex();update();}
    @Override public void onPause(){super.onPause();visible=false;queryGeneration++;search.observe(null);SemanticModel.observe(null);}
    @Override public void onFragmentDestroy(){visible=false;queryGeneration++;if(search!=null)search.observe(null);SemanticModel.observe(null);super.onFragmentDestroy();}
}
