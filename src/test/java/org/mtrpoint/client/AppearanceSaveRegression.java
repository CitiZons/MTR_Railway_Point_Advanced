package org.mtrpoint.client;

import org.mtrpoint.geometry.PointSettings;
import java.util.List;

public final class AppearanceSaveRegression {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){run();}
    public static void run(){
        var queue=new AppearanceSaveQueue();var first=PointSettings.DEFAULT.with(0,1.2);var second=PointSettings.DEFAULT.with(0,1.5);
        var a=new AppearanceSaveQueue.Request("a",3,first);var b=new AppearanceSaveQueue.Request("b",7,second);
        queue.begin(List.of(a,b));
        require(queue.acknowledge("b",8,second,"mtrpoint.saved")==AppearanceSaveQueue.Reply.IGNORED&&queue.current()==a,"Unrelated broadcast cannot advance the current save");
        require(queue.acknowledge("a",3,first,"mtrpoint.saved")==AppearanceSaveQueue.Reply.IGNORED,"Old acknowledgement cannot consume a retry");
        require(queue.acknowledge("a",4,first,"mtrpoint.saved")==AppearanceSaveQueue.Reply.SAVED&&queue.current()==b,"First successful save advances to the second request");
        require(queue.acknowledge("b",8,second,"mtrpoint.saved")==AppearanceSaveQueue.Reply.SAVED&&queue.current()==null,"Both drafts save in order");
        for(String message:List.of("mtrpoint.denied","mtrpoint.stale","mtrpoint.too_far","mtrpoint.invalid")){
            queue.begin(List.of(a,b));require(queue.acknowledge("a",3,PointSettings.DEFAULT,message)==AppearanceSaveQueue.Reply.REJECTED&&queue.current()==null,"Rejection stops the queue without acknowledging a draft: "+message);
        }
        queue.begin(List.of(a,b));require(queue.acknowledge("a",4,second,"mtrpoint.saved")==AppearanceSaveQueue.Reply.CONFLICT&&queue.current()==null,"Another player's different save retains the draft");
        queue.begin(List.of(a,b));queue.cancel();require(queue.acknowledge("a",4,first,"mtrpoint.saved")==AppearanceSaveQueue.Reply.IGNORED,"A late reply after timeout does not restart the queue");
        System.out.println("PASS: appearance save queue matches ID, revision and value; saves two drafts and stops on rejection/conflict/timeout");
    }
}
