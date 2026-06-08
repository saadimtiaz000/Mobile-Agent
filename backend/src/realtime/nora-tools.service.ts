import {
  BadRequestException,
  Injectable,
  InternalServerErrorException,
} from "@nestjs/common";

export type RealtimeToolDefinition = {
  type: "function";
  name: string;
  description: string;
  parameters: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
    additionalProperties: boolean;
  };
};

type JsonRecord = Record<string, unknown>;

type WeatherGeoResponse = {
  results?: WeatherGeoResult[];
};

type WeatherGeoResult = {
  name?: string;
  latitude?: number;
  longitude?: number;
  country?: string;
  admin1?: string;
  timezone?: string;
};

type WeatherForecastResponse = {
  current?: {
    time?: string;
    temperature_2m?: number;
    apparent_temperature?: number;
    relative_humidity_2m?: number;
    precipitation?: number;
    weather_code?: number;
    wind_speed_10m?: number;
  };
  daily?: {
    time?: string[];
    temperature_2m_max?: number[];
    temperature_2m_min?: number[];
    precipitation_probability_max?: number[];
    weather_code?: number[];
  };
};

type NewsHeadline = {
  title: string;
  source?: string;
  publishedAt?: string;
};

export const NORA_REALTIME_TOOLS: RealtimeToolDefinition[] = [
  {
    type: "function",
    name: "get_current_weather",
    description:
      "Get live current weather and a short forecast for a city or place. Use this when the user asks for weather, temperature, rain, wind, or forecast updates.",
    parameters: {
      type: "object",
      properties: {
        location: {
          type: "string",
          description:
            "City, region, and country if known, for example 'New York, US' or 'Karachi, Pakistan'. Ask the user for a city if it is missing.",
        },
      },
      required: ["location"],
      additionalProperties: false,
    },
  },
  {
    type: "function",
    name: "get_live_news",
    description:
      "Get live news headlines. Use this when the user asks for latest news, breaking news, live updates, or news about a topic.",
    parameters: {
      type: "object",
      properties: {
        topic: {
          type: "string",
          description:
            "Optional news topic, company, place, or event. Leave empty for general top headlines.",
        },
        region: {
          type: "string",
          description:
            "Optional two-letter region code for headlines, such as US, GB, PK, IN, or AE. Defaults to US.",
        },
      },
      additionalProperties: false,
    },
  },
];

@Injectable()
export class NoraToolsService {
  async execute(name: string, rawArguments: unknown) {
    const args = this.parseArguments(rawArguments);

    switch (name) {
      case "get_current_weather":
        return this.getCurrentWeather(args);
      case "get_live_news":
        return this.getLiveNews(args);
      default:
        throw new BadRequestException(`Unknown Nora tool: ${name}`);
    }
  }

  private async getCurrentWeather(args: JsonRecord) {
    const location = this.readString(args, "location");
    if (!location) {
      throw new BadRequestException("A location is required for weather.");
    }

    const match = await this.findWeatherLocation(location);

    if (!match?.name || match.latitude === undefined || match.longitude === undefined) {
      throw new BadRequestException(`No weather location found for "${location}".`);
    }

    const forecastUrl = new URL("https://api.open-meteo.com/v1/forecast");
    forecastUrl.searchParams.set("latitude", String(match.latitude));
    forecastUrl.searchParams.set("longitude", String(match.longitude));
    forecastUrl.searchParams.set(
      "current",
      "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m",
    );
    forecastUrl.searchParams.set(
      "daily",
      "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
    );
    forecastUrl.searchParams.set("forecast_days", "3");
    forecastUrl.searchParams.set("timezone", "auto");

    const forecast = await this.fetchJson<WeatherForecastResponse>(
      forecastUrl.toString(),
    );
    const current = forecast.current ?? {};
    const daily = forecast.daily ?? {};

    return {
      type: "weather",
      source: "Open-Meteo",
      fetchedAt: new Date().toISOString(),
      location: {
        name: match.name,
        region: match.admin1,
        country: match.country,
        latitude: match.latitude,
        longitude: match.longitude,
        timezone: match.timezone,
      },
      current: {
        time: current.time,
        temperatureC: current.temperature_2m,
        feelsLikeC: current.apparent_temperature,
        condition: this.weatherDescription(current.weather_code),
        humidityPercent: current.relative_humidity_2m,
        precipitationMm: current.precipitation,
        windKph: current.wind_speed_10m,
      },
      forecast: (daily.time ?? []).slice(0, 3).map((date, index) => ({
        date,
        condition: this.weatherDescription(daily.weather_code?.[index]),
        highC: daily.temperature_2m_max?.[index],
        lowC: daily.temperature_2m_min?.[index],
        precipitationChancePercent:
          daily.precipitation_probability_max?.[index],
      })),
    };
  }

  private async findWeatherLocation(
    location: string,
  ): Promise<WeatherGeoResult | undefined> {
    const countryCode = this.findCountryCode(location);
    const candidates = [
      location,
      location.split(",")[0]?.trim(),
      location.split(/\s+/).slice(0, -1).join(" ").trim(),
    ].filter((candidate): candidate is string => Boolean(candidate));

    for (const candidate of [...new Set(candidates)]) {
      const geoUrl = new URL("https://geocoding-api.open-meteo.com/v1/search");
      geoUrl.searchParams.set("name", candidate);
      geoUrl.searchParams.set("count", "1");
      geoUrl.searchParams.set("language", "en");
      geoUrl.searchParams.set("format", "json");
      if (countryCode) {
        geoUrl.searchParams.set("countryCode", countryCode);
      }

      const geo = await this.fetchJson<WeatherGeoResponse>(geoUrl.toString());
      const match = geo.results?.find(
        (result) =>
          typeof result.latitude === "number" &&
          typeof result.longitude === "number",
      );
      if (match) return match;
    }

    return undefined;
  }

  private findCountryCode(location: string): string | undefined {
    const normalized = location.trim().toUpperCase();
    const twoLetterCode = normalized
      .split(/[,\s]+/)
      .find((part) => /^[A-Z]{2}$/.test(part));

    if (twoLetterCode) return twoLetterCode;

    const countryNames: Record<string, string> = {
      "UNITED STATES": "US",
      USA: "US",
      AMERICA: "US",
      PAKISTAN: "PK",
      INDIA: "IN",
      "UNITED KINGDOM": "GB",
      UK: "GB",
      CANADA: "CA",
      AUSTRALIA: "AU",
      "UNITED ARAB EMIRATES": "AE",
      UAE: "AE",
    };

    return countryNames[normalized.split(",").at(-1)?.trim() ?? ""];
  }

  private async getLiveNews(args: JsonRecord) {
    const topic = this.readString(args, "topic");
    const region = this.normalizeRegion(
      this.readString(args, "region") ?? process.env.NORA_NEWS_REGION ?? "US",
    );
    const language = process.env.NORA_NEWS_LANGUAGE ?? "en-US";
    const url = topic
      ? this.googleNewsSearchUrl(topic, region, language)
      : this.googleNewsTopUrl(region, language);

    const xml = await this.fetchText(url, "application/rss+xml");
    const headlines = this.compactNewsHeadlines(this.parseGoogleNewsRss(xml), 5);

    if (headlines.length === 0) {
      throw new InternalServerErrorException("No live news headlines were found.");
    }

    const spokenBrief = headlines
      .map((headline, index) => {
        const source = headline.source ? `, ${headline.source}` : "";
        return `${index + 1}. ${headline.title}${source}`;
      })
      .join(" ");

    return {
      type: "news",
      source: "Google News RSS",
      fetchedAt: new Date().toISOString(),
      region,
      topic: topic ?? "top headlines",
      spokenBrief,
      headlines,
    };
  }

  private googleNewsTopUrl(region: string, language: string): string {
    const url = new URL("https://news.google.com/rss");
    url.searchParams.set("hl", language);
    url.searchParams.set("gl", region);
    url.searchParams.set("ceid", `${region}:en`);
    return url.toString();
  }

  private googleNewsSearchUrl(
    topic: string,
    region: string,
    language: string,
  ): string {
    const url = new URL("https://news.google.com/rss/search");
    url.searchParams.set("q", topic.slice(0, 120));
    url.searchParams.set("hl", language);
    url.searchParams.set("gl", region);
    url.searchParams.set("ceid", `${region}:en`);
    return url.toString();
  }

  private parseGoogleNewsRss(xml: string): NewsHeadline[] {
    const items = [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)];
    const headlines: NewsHeadline[] = [];

    for (const match of items) {
      const item = match[1] ?? "";
      const title = this.readXmlTag(item, "title");
      if (!title) continue;

      headlines.push({
        title: this.cleanNewsTitle(title, this.readXmlTag(item, "source")),
        source: this.readXmlTag(item, "source"),
        publishedAt: this.readXmlTag(item, "pubDate"),
      });
    }

    return headlines;
  }

  private compactNewsHeadlines(
    headlines: NewsHeadline[],
    limit: number,
  ): NewsHeadline[] {
    const seen = new Set<string>();
    const compact: NewsHeadline[] = [];

    for (const headline of headlines) {
      const normalized = headline.title.toLowerCase().replace(/[^a-z0-9]+/g, " ");
      if (seen.has(normalized)) continue;

      seen.add(normalized);
      compact.push({
        title: headline.title.slice(0, 150),
        source: headline.source?.slice(0, 60),
        publishedAt: headline.publishedAt,
      });

      if (compact.length >= limit) break;
    }

    return compact;
  }

  private cleanNewsTitle(title: string, source?: string): string {
    if (!source) return title.trim();

    const sourceSuffix = new RegExp(`\\s+-\\s+${this.escapeRegExp(source)}$`, "i");
    return title.replace(sourceSuffix, "").trim();
  }

  private escapeRegExp(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  private readXmlTag(xml: string, tag: string): string | undefined {
    const match = xml.match(
      new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)<\\/${tag}>`, "i"),
    );
    if (!match?.[1]) return undefined;

    return this.decodeXml(this.stripCdata(match[1]))
      .replace(/<[^>]*>/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  private stripCdata(value: string): string {
    return value.replace(/^<!\[CDATA\[/, "").replace(/\]\]>$/, "");
  }

  private decodeXml(value: string): string {
    return value
      .replace(/&#x([a-f\d]+);/gi, (_, hex: string) =>
        String.fromCodePoint(Number.parseInt(hex, 16)),
      )
      .replace(/&#(\d+);/g, (_, code: string) =>
        String.fromCodePoint(Number.parseInt(code, 10)),
      )
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
  }

  private parseArguments(rawArguments: unknown): JsonRecord {
    if (typeof rawArguments === "string") {
      if (!rawArguments.trim()) return {};

      const parsed = this.parseJson(rawArguments);
      if (this.isJsonRecord(parsed)) return parsed;
      throw new BadRequestException("Tool arguments must be a JSON object.");
    }

    if (this.isJsonRecord(rawArguments)) return rawArguments;
    if (rawArguments === undefined || rawArguments === null) return {};

    throw new BadRequestException("Tool arguments must be a JSON object.");
  }

  private readString(args: JsonRecord, key: string): string | undefined {
    const value = args[key];
    return typeof value === "string" && value.trim()
      ? value.trim()
      : undefined;
  }

  private normalizeRegion(region: string): string {
    const normalized = region.trim().toUpperCase();
    return /^[A-Z]{2}$/.test(normalized) ? normalized : "US";
  }

  private isJsonRecord(value: unknown): value is JsonRecord {
    return typeof value === "object" && value !== null && !Array.isArray(value);
  }

  private parseJson(value: string): unknown {
    try {
      return JSON.parse(value) as unknown;
    } catch {
      throw new BadRequestException("Tool arguments must be valid JSON.");
    }
  }

  private async fetchJson<T>(url: string): Promise<T> {
    const text = await this.fetchText(url, "application/json");
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new InternalServerErrorException("Live data returned invalid JSON.");
    }
  }

  private async fetchText(url: string, accept: string): Promise<string> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8_000);

    try {
      const response = await fetch(url, {
        headers: { Accept: accept },
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new InternalServerErrorException(
          `Live data request failed: ${response.status}`,
        );
      }

      return response.text();
    } catch (error) {
      if (error instanceof InternalServerErrorException) throw error;
      throw new InternalServerErrorException(
        "Nora could not reach the live data service.",
      );
    } finally {
      clearTimeout(timeout);
    }
  }

  private weatherDescription(code?: number): string | undefined {
    if (code === undefined) return undefined;

    const descriptions: Record<number, string> = {
      0: "clear sky",
      1: "mainly clear",
      2: "partly cloudy",
      3: "overcast",
      45: "fog",
      48: "depositing rime fog",
      51: "light drizzle",
      53: "moderate drizzle",
      55: "dense drizzle",
      61: "slight rain",
      63: "moderate rain",
      65: "heavy rain",
      71: "slight snow",
      73: "moderate snow",
      75: "heavy snow",
      80: "slight rain showers",
      81: "moderate rain showers",
      82: "violent rain showers",
      95: "thunderstorm",
      96: "thunderstorm with slight hail",
      99: "thunderstorm with heavy hail",
    };

    return descriptions[code] ?? `weather code ${code}`;
  }
}
