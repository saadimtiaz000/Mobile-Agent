import { Module } from "@nestjs/common";
import { NoraToolsService } from "./realtime/nora-tools.service";
import { RealtimeController } from "./realtime/realtime.controller";
import { RealtimeService } from "./realtime/realtime.service";

@Module({
  controllers: [RealtimeController],
  providers: [RealtimeService, NoraToolsService],
})
export class AppModule {}
