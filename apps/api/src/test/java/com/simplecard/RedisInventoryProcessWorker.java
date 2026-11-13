package com.simplecard;
import com.simplecard.service.InventoryLifecycleService;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.util.*;
public class RedisInventoryProcessWorker {
    public static void main(String[] args) throws Throwable {
        String op=args[0];UUID id=UUID.fromString(args[1]);Path gate=Path.of(args[2]),result=Path.of(args[3]);
        try(var context=SpringApplication.run(SimpleCardApplication.class,
            "--server.port=0", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/inventory_test",
            "--spring.datasource.username=inventory_test", "--spring.datasource.password=inventory_test",
            "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect", "--spring.sql.init.mode=never",
            "--spring.mail.host=localhost", "--mail.enabled=false", "--payment-inbox.worker-enabled=false",
            "--coordination.enabled=true", "--coordination.address=redis://127.0.0.1:56379", "--coordination.password=",
            "--coordination.prefix=inventory-redis-test", "--coordination.watchdog-ms=2000", "--coordination.wait-ms=3000",
            "--logging.level.root=OFF", "--logging.level.com.simplecard=OFF", "--spring.main.banner-mode=off")) {
            Files.writeString(result.resolveSibling(result.getFileName()+".ready"),"ready");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(!Files.exists(gate)&&System.nanoTime()<deadline)Thread.sleep(50);
            if(!Files.exists(gate))throw new IllegalStateException("start gate timeout");
            var inventory=context.getBean(InventoryLifecycleService.class);
            if(op.equals("hold")) {
                var coordinator=context.getBean(com.simplecard.service.RedisOrderCoordinator.class);
                var itemRepo=context.getBean(com.simplecard.repository.OrderItemRepository.class);
                java.util.List<String> keys=new java.util.ArrayList<>(); keys.add(coordinator.orderKey(id));
                itemRepo.findByOrderId(id).forEach(i->keys.add(coordinator.stockKey(i.getProductId(),i.getSpecId())));
                coordinator.execute(keys,()->{Files.writeString(result.resolveSibling(result.getFileName()+".held"),"held");Thread.sleep(30000);return null;});
            } else if(op.equals("pay"))inventory.confirmPayment(id,null);else inventory.close(id,true);
            Files.writeString(result,"OK");
        } catch(Throwable t) {Files.writeString(result,t.getClass().getName());throw t;}
    }
}
