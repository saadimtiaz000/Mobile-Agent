import "dotenv/config";
import { NestFactory } from "@nestjs/core";
import * as express from "express";
import { AppModule } from "./app.module";

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  const corsOrigin = process.env.CORS_ORIGIN ?? "*";

  app.enableCors({
    origin: corsOrigin === "*" ? true : corsOrigin,
  });

  app.use(
    "/api/realtime/sdp",
    express.text({
      type: ["application/sdp", "text/plain", "*/*"],
      limit: "1mb",
    }),
  );

  const port = Number(process.env.PORT ?? 3000);
  await app.listen(port);
}

void bootstrap();
