package org.mtrpoint.client;

import org.mtrpoint.geometry.PointSettings;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/** Matches broadcasts to the exact appearance currently being saved. Drafts belong to the editor. */
final class AppearanceSaveQueue {
    record Request(String id,long revision,PointSettings value) {}
    enum Reply { IGNORED, SAVED, REJECTED, CONFLICT }
    private final Deque<Request> requests=new ArrayDeque<>();
    void begin(Collection<Request> next){requests.clear();requests.addAll(next);}
    Request current(){return requests.peek();}
    void cancel(){requests.clear();}
    Reply acknowledge(String id,long revision,PointSettings value,String message){
        Request request=current();
        if(request==null||!request.id().equals(id)||message==null||message.isEmpty())return Reply.IGNORED;
        if(message.equals("mtrpoint.saved")){
            if(revision<=request.revision())return Reply.IGNORED;
            if(revision!=request.revision()+1||!request.value().equals(value)){cancel();return Reply.CONFLICT;}
            requests.remove();return Reply.SAVED;
        }
        if(message.equals("mtrpoint.stale")||message.equals("mtrpoint.denied")||message.equals("mtrpoint.too_far")||message.equals("mtrpoint.invalid")){
            cancel();return Reply.REJECTED;
        }
        return Reply.IGNORED;
    }
}
