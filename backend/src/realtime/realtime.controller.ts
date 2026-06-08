import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  Headers,
  Post,
  Query,
} from "@nestjs/common";
import { RealtimeService } from "./realtime.service";
import { NoraToolsService } from "./nora-tools.service";

@Controller("api/realtime")
export class RealtimeController {
  constructor(
    private readonly realtimeService: RealtimeService,
    private readonly noraToolsService: NoraToolsService,
  ) {}

  @Post("sdp")
  @Header("Content-Type", "application/sdp")
  async exchangeSdp(
    @Body() offerSdp: string,
    @Headers("x-user-id") userId = "anonymous",
  ): Promise<string> {
    return this.realtimeService.exchangeSdp(offerSdp, userId);
  }

  @Get("client-secret")
  async createClientSecret(
    @Headers("x-user-id") userId = "anonymous",
    @Query("voice") voice?: string,
  ) {
    return this.realtimeService.createClientSecret(userId, voice);
  }

  @Get("status")
  status() {
    return this.realtimeService.status();
  }

  @Post("tool")
  async executeTool(
    @Body() body: { name?: string; arguments?: unknown },
  ): Promise<unknown> {
    if (!body.name) {
      throw new BadRequestException("Missing tool name");
    }
    return this.noraToolsService.execute(body.name, body.arguments);
  }
}
