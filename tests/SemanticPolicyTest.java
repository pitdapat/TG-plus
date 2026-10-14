import org.telegram.messenger.semantic.SemanticPolicy;
import org.telegram.messenger.semantic.SemanticTasks;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SemanticPolicyTest {
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    public static void main(String[] args) {
        check(SemanticPolicy.canRun(true,true,true),"Automatic job can start while charging");
        check(!SemanticPolicy.canRun(true,true,false),"Unplugging stops an automatic job at its next checkpoint");
        check(SemanticPolicy.canRun(false,true,false),"Explicit Index now may run unplugged");
        check(SemanticPolicy.canRun(true,false,false),"Charging restriction is optional");
        String same="unchanged message";
        check(SemanticPolicy.eligible(1,same,false,false,false),"Ordinary downloaded message eligible");
        check(!SemanticPolicy.eligible(1,same,true,false,false),"Same text becomes protected");
        check(!SemanticPolicy.eligible(1,same,false,true,false),"Same text becomes expiring");
        check(!SemanticPolicy.eligible(1,same,false,false,true),"Service content excluded");
        check(!SemanticPolicy.eligible(1,null,false,false,false),"Missing text excluded");
        ArrayList<SemanticPolicy.Entry> entries=new ArrayList<>();
        entries.add(new SemanticPolicy.Entry(2,20,200,4));
        entries.add(new SemanticPolicy.Entry(1,10,100,3));
        ArrayList<SemanticPolicy.Entry> victims=SemanticPolicy.victims(10000,0,5,300,entries);
        check(victims!=null&&victims.size()==2&&victims.get(0).date==100,"Newest message evicts oldest whole messages");
        check(SemanticPolicy.victims(10000,0,5,50,entries)==null,"Old history cannot evict newer text");
        check(SemanticPolicy.victims(10000,4,4,50,entries).isEmpty(),"Equal-size edit at capacity needs no eviction");
        check(SemanticPolicy.victims(10000,4,5,300,entries).size()==1,"Larger edit counts replaced chunks");
        check(SemanticPolicy.victims(9999,0,1,50,entries).isEmpty(),"Free capacity accepts old history");
        check(SemanticPolicy.victims(10000,0,10001,300,entries)==null,"Oversized message rejected without deleting anything");
        check(SemanticPolicy.victims(10000,0,5,200,entries)==null,"Insufficient older capacity leaves index intact");
        check(entries.get(0).date==200,"Planning does not mutate input");
        AtomicInteger failed=new AtomicInteger(),ran=new AtomicInteger();
        SemanticTasks.run(()->{throw new IllegalStateException("database failure");},e->failed.incrementAndGet());
        SemanticTasks.run(()->{throw new NoClassDefFoundError("runtime failure");},e->failed.incrementAndGet());
        SemanticTasks.run(ran::incrementAndGet,e->failed.incrementAndGet());
        check(failed.get()==2&&ran.get()==1,"Cleanup failures are contained and subsequent work still runs");
        Random random=new Random(7134);
        for(int attempt=0;attempt<500;attempt++) {
            int used=9000+random.nextInt(1001),replacing=random.nextInt(10),incoming=1+random.nextInt(30);
            ArrayList<SemanticPolicy.Entry> older=new ArrayList<>();
            for(int n=0;n<20;n++)older.add(new SemanticPolicy.Entry(n,n,n,1+random.nextInt(5)));
            victims=SemanticPolicy.victims(used,replacing,incoming,100,older);
            if(victims!=null){int freed=0;for(SemanticPolicy.Entry e:victims)freed+=e.chunks;check(used-replacing+incoming-freed<=10000,"Capacity preserved");}
        }
        System.out.println("PASS charging transitions, protection/expiry, bounded newer-message admission, edits and recoverable cleanup failures");
    }
}
