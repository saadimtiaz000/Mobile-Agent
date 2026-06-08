import { createHash } from "crypto";
import {
  BadRequestException,
  Injectable,
  InternalServerErrorException,
  ServiceUnavailableException,
} from "@nestjs/common";
import {
  NORA_REALTIME_TOOLS,
  RealtimeToolDefinition,
} from "./nora-tools.service";

type NoraSessionConfig = {
  type: "realtime";
  model: string;
  output_modalities: ["audio"];
  instructions: string;
  audio: {
    input: {
      format: {
        type: "audio/pcm";
        rate: 24000;
      };
      noise_reduction: {
        type: "near_field" | "far_field";
      };
      transcription: {
        model: string;
        language: string;
        prompt: string;
      };
      turn_detection: {
        type: "server_vad";
        threshold: number;
        prefix_padding_ms: number;
        silence_duration_ms: number;
        create_response: true;
        interrupt_response: true;
      };
    };
    output: {
      format: {
        type: "audio/pcm";
        rate: 24000;
      };
      voice: string;
    };
  };
  reasoning: {
    effort: "low" | "medium" | "high";
  };
  tools: RealtimeToolDefinition[];
  tool_choice: "auto";
};

@Injectable()
export class RealtimeService {
  private readonly model = process.env.OPENAI_REALTIME_MODEL ?? "gpt-realtime-2";
  private readonly defaultVoice = process.env.NORA_VOICE ?? "marin";
  private readonly inputLanguage = process.env.NORA_INPUT_LANGUAGE ?? "en";
  private readonly noiseReduction = this.readNoiseReduction();
  private readonly vadThreshold = this.readBoundedNumber(
    process.env.NORA_VAD_THRESHOLD,
    0.68,
    0,
    1,
  );
  private readonly vadSilenceMs = this.readBoundedInteger(
    process.env.NORA_VAD_SILENCE_MS,
    760,
    300,
    1600,
  );
  private readonly vadPrefixMs = this.readBoundedInteger(
    process.env.NORA_VAD_PREFIX_MS,
    420,
    100,
    1000,
  );
  private readonly transcriptionModel =
    process.env.NORA_TRANSCRIPTION_MODEL ?? "gpt-4o-transcribe";

  status() {
    const ready = this.hasApiKey();
    return {
      ready,
      model: this.model,
      voice: this.defaultVoice,
      message: ready
        ? "Nora backend is ready."
        : "Nora backend is missing OPENAI_API_KEY. Add it to backend/.env and restart the backend.",
    };
  }

  async exchangeSdp(offerSdp: string, userId: string): Promise<string> {
    if (!offerSdp?.trim()) {
      throw new BadRequestException("Missing SDP offer");
    }

    const formData = new FormData();
    formData.set("sdp", offerSdp);
    formData.set("session", JSON.stringify(this.createSessionConfig()));

    const response = await fetch("https://api.openai.com/v1/realtime/calls", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.requireApiKey()}`,
        "OpenAI-Safety-Identifier": this.hashUserId(userId),
      },
      body: formData,
    });

    if (!response.ok) {
      const message = await response.text();
      throw new InternalServerErrorException(
        `OpenAI Realtime SDP exchange failed: ${response.status} ${message}`,
      );
    }

    return response.text();
  }

  async createClientSecret(userId: string, voice?: string) {
    const body = {
      session: this.createSessionConfig(voice),
    };

    const response = await fetch(
      "https://api.openai.com/v1/realtime/client_secrets",
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${this.requireApiKey()}`,
          "Content-Type": "application/json",
          "OpenAI-Safety-Identifier": this.hashUserId(userId),
        },
        body: JSON.stringify(body),
      },
    );

    if (!response.ok) {
      const message = await response.text();
      throw new InternalServerErrorException(
        `OpenAI Realtime client secret failed: ${response.status} ${message}`,
      );
    }

    return response.json();
  }

  private createSessionConfig(voice = this.defaultVoice): NoraSessionConfig {
    return {
      type: "realtime",
      model: this.model,
      output_modalities: ["audio"],
      instructions:
        "You are Nora, a warm, concise mobile AI agent. Respond naturally in voice. " +
        "When the user says Nora, treat it as your name. Ask brief clarifying questions when needed. " +
        "Only respond to clear user speech directed at Nora. Ignore background chatter, music, TV audio, keyboard taps, traffic, and accidental noise. " +
        "If the user audio is unclear or you are not confident what was asked, say briefly that you did not catch it and ask them to repeat. " +
        "Use live tools for current weather, forecasts, latest headlines, breaking news, and topic news. " +
        "For weather, ask for the city if the user has not provided a location or if a tool result says status needs_location. " +
        "For live updates, answer briefly with the freshest details and mention the source. Prefer the tool result's spokenBrief when present. " +
        "Never claim you can bypass Android lock-screen security.",
      audio: {
        input: {
          format: {
            type: "audio/pcm",
            rate: 24000,
          },
          noise_reduction: {
            type: this.noiseReduction,
          },
          transcription: {
            model: this.transcriptionModel,
            language: this.inputLanguage,
            prompt:
              "Nora is a mobile voice agent. Expect concise user requests about calls, weather, live news, phone tasks, reminders, and general questions. Ignore background noise.",
          },
          turn_detection: {
            type: "server_vad",
            threshold: this.vadThreshold,
            prefix_padding_ms: this.vadPrefixMs,
            silence_duration_ms: this.vadSilenceMs,
            create_response: true,
            interrupt_response: true,
          },
        },
        output: {
          format: {
            type: "audio/pcm",
            rate: 24000,
          },
          voice,
        },
      },
      reasoning: {
        effort: "medium",
      },
      tools: NORA_REALTIME_TOOLS,
      tool_choice: "auto",
    };
  }

  private requireApiKey(): string {
    const apiKey = process.env.OPENAI_API_KEY;
    if (!apiKey) {
      throw new ServiceUnavailableException(
        "Nora backend is missing OPENAI_API_KEY. Add it to backend/.env and restart the backend.",
      );
    }
    return apiKey;
  }

  private hasApiKey(): boolean {
    return Boolean(process.env.OPENAI_API_KEY?.trim());
  }

  private hashUserId(userId: string): string {
    return createHash("sha256").update(userId || "anonymous").digest("hex");
  }

  private readNoiseReduction(): "near_field" | "far_field" {
    return process.env.NORA_NOISE_REDUCTION === "far_field"
      ? "far_field"
      : "near_field";
  }

  private readBoundedNumber(
    value: string | undefined,
    fallback: number,
    min: number,
    max: number,
  ): number {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(Math.max(parsed, min), max);
  }

  private readBoundedInteger(
    value: string | undefined,
    fallback: number,
    min: number,
    max: number,
  ): number {
    return Math.round(this.readBoundedNumber(value, fallback, min, max));
  }
}
