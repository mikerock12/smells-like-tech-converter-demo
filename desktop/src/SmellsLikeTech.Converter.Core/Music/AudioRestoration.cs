using System.Text.Json;
using System.Text.Json.Nodes;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>
/// Restaurar áudio com IA (experimental): o diagnóstico do núcleo nativo vira etapas e
/// parâmetros do reparo. Espelho de packages/converter-core/restaurador.mjs, conferido pelos
/// casos de restaurador-casos.json. Conservador: sem defeito suficiente, nada muda; o que a
/// gravação perdeu não volta com filtro; modelos de IA ainda em revisão de licença não entram.
/// </summary>
public static class AudioRestoration
{
    public const int Version = 1;

    public static readonly IReadOnlyList<(string Id, string Label, string Summary)> Profiles =
    [
        ("auto", "Automático", "Analisa e trata só o que encontrar, com cuidado."),
        ("lossy", "MP3 / música comprimida", "Com a IA ligada, o Apollo refaz o que a compressão destruiu; sem ela, só a limpeza leve."),
        ("live", "Show ao vivo / gravação de fã", "Ruído, zumbido e recorte, preservando a plateia e a ambiência."),
        ("archive", "Fita, vinil ou gravação antiga", "Zumbido, estalos, chiado e um pouco de brilho."),
        ("manual", "Avançado (eu escolho)", "Você liga e dosa cada etapa."),
    ];

    public static readonly IReadOnlyList<(string Id, string Label)> Steps =
    [
        ("compressao", "Reparo de compressão por IA (Apollo)"), ("dc", "Deslocamento DC"), ("zumbido", "Zumbido da rede (50/60 Hz)"), ("estalos", "Estalos e cliques"),
        ("recorte", "Recorte (distorção por volume alto)"), ("ruido", "Chiado e ruído de fundo"),
        ("grave", "Ronco e vento (corte de grave)"), ("brilho", "Brilho (agudos)"),
    ];

    public static readonly IReadOnlyList<string> Formats = ["wav-24", "flac", "wav-32f", "mp3"];

    public sealed record Options(string Profile, int Intensity, int PreserveAmbience, string Format, IReadOnlyDictionary<string, int> Steps, bool Report, bool Ai = false);

    /// <summary>O reparo de compressão por IA (Apollo) e quanto dele entra na mistura.</summary>
    public sealed record AiStep(string Model, double Mix, double CutoffHz);

    public sealed record Problem(double Severity, double Confidence, double? Hz = null);

    public sealed record Evaluation(IReadOnlyDictionary<string, Problem> Problems, IReadOnlyList<string> Recommendations, IReadOnlyList<string> Warnings, bool NoDefects);

    public sealed record Plan(string Profile, IReadOnlyList<(string Id, double Amount)> Steps, IReadOnlyDictionary<string, double> Parameters,
        IReadOnlyList<string> Warnings, bool NoChange, Evaluation Evaluation, AiStep? Ai = null);

    private static double Clamp(double v, double a, double b) => Math.Min(b, Math.Max(a, v));
    // Como o Math.round do JavaScript (metade para cima), para os três núcleos baterem.
    private static double Round4(double v) => Math.Floor(v * 1e4 + 0.5) / 1e4;
    private static int RoundJs(double v) => (int)Math.Floor(v + 0.5);

    private static double Number(JsonNode? node, double fallback)
    {
        if (node is null) return fallback;
        try
        {
            if (node is JsonValue value)
            {
                if (value.TryGetValue<double>(out var d) && double.IsFinite(d)) return d;
                if (value.TryGetValue<string>(out var s) && double.TryParse(s, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var p) && double.IsFinite(p)) return p;
            }
        }
        catch (InvalidOperationException) { }
        return fallback;
    }

    /// <summary>As opções como viajam na conversão (texto JSON), normalizadas.</summary>
    public static Options ReadOptions(string? json)
    {
        JsonObject o;
        try { o = string.IsNullOrWhiteSpace(json) ? [] : JsonNode.Parse(json) as JsonObject ?? []; }
        catch (JsonException) { throw new ConversionException("invalid_options", "As opções da restauração estão corrompidas. Escolha de novo."); }
        var profile = o["perfil"] is JsonValue pv && pv.TryGetValue<string>(out var p) && Profiles.Any(x => x.Id == p) ? p : "auto";
        var etapas = o["etapas"] as JsonObject;
        var steps = Steps.ToDictionary(s => s.Id, s => (int)Clamp(RoundJs(Number(etapas?[s.Id], 0)), s.Id == "brilho" ? -100 : 0, 100));
        var format = o["formato"] is JsonValue fv && fv.TryGetValue<string>(out var f) && Formats.Contains(f) ? f : "wav-24";
        var report = o["relatorio"] is JsonValue rv && rv.TryGetValue<bool>(out var r) && r;
        var ai = o["ia"] is JsonValue iv && iv.TryGetValue<bool>(out var ia) && ia;
        return new Options(profile, (int)Clamp(RoundJs(Number(o["intensidade"], 35)), 0, 100), (int)Clamp(RoundJs(Number(o["preservarAmbiencia"], 80)), 0, 100), format, steps, report, ai);
    }

    private static double Get(IReadOnlyDictionary<string, double> d, string k) => d.TryGetValue(k, out var v) ? v : 0;

    public static Evaluation Evaluate(IReadOnlyDictionary<string, double> d)
    {
        var nyquist = Get(d, "taxa") / 2;
        var recorte = Get(d, "recorte") > 1e-4 ? Clamp(Math.Log10(Get(d, "recorte") / 1e-4) / 2, 0, 1) : 0;
        var zumbido = Clamp((Get(d, "zumbidoDb") - 6) / 14, 0, 1);
        var plano = Clamp((Get(d, "planuraDoRuido") - 0.15) / 0.45, 0, 1);
        var ruido = Clamp((Get(d, "pisoDeRuidoDb") + 70) / 30, 0, 1) * plano;
        var estalos = Clamp((Get(d, "estalosPorMinuto") - 3) / 40, 0, 1);
        var dc = Clamp((Math.Abs(Get(d, "dc")) - 0.003) / 0.027, 0, 1);
        var bandaHz = Get(d, "bandaHz");
        var banda = bandaHz > 0 && bandaHz < 0.85 * nyquist && bandaHz < 17000 ? Clamp((17000 - bandaHz) / 7000, 0, 1) : 0;
        var problems = new Dictionary<string, Problem>
        {
            ["recorte"] = new(Round4(recorte), Get(d, "picoDb") > -1 ? 0.8 : 0.6),
            ["zumbido"] = new(Round4(zumbido), 0.7, Get(d, "zumbidoHz")),
            ["ruido"] = new(Round4(ruido), Round4(0.4 + 0.4 * plano)),
            ["estalos"] = new(Round4(estalos), 0.6),
            ["dc"] = new(Round4(dc), 0.9),
            ["banda"] = new(Round4(banda), 0.5, Math.Floor(bandaHz + 0.5)),
        };
        var recommendations = new[] { "recorte", "zumbido", "ruido", "estalos", "dc" }.Where(id => problems[id].Severity >= 0.15).ToList();
        var warnings = new List<string> { "o_diagnostico_nao_garante_a_origem" };
        if (banda >= 0.15) warnings.Add("banda_limitada");
        if (Get(d, "silencio") > 0.9) warnings.Add("quase_silencio");
        return new Evaluation(problems, recommendations, warnings, recommendations.Count == 0);
    }

    public static Plan Build(IReadOnlyDictionary<string, double> d, Options options)
    {
        var evaluation = Evaluate(d);
        var p = MusicNative.RepairParameters.Where(n => n != "reservado").ToDictionary(n => n, _ => 0.0);
        var warnings = new List<string>(evaluation.Warnings);
        var s = options.Intensity / 100.0;
        double G(string id) => evaluation.Problems[id].Severity;
        var level = Math.Min(1, Math.Pow(10, Get(d, "picoDb") / 20)) * 0.995;
        var hum = Get(d, "zumbidoHz") == 60 ? 60 : 50;

        if (options.Profile == "manual")
        {
            var e = options.Steps;
            if (e["dc"] > 0) p["dc"] = 1;
            if (e["zumbido"] > 0) { p["zumbidoHz"] = hum; p["zumbido"] = e["zumbido"] / 100.0; }
            p["estalos"] = e["estalos"] / 100.0;
            if (e["recorte"] > 0) { p["recorte"] = e["recorte"] / 100.0; p["nivelDoRecorte"] = level; }
            p["ruido"] = e["ruido"] / 100.0;
            if (e["grave"] > 0) p["corteGraveHz"] = 20 + 0.4 * e["grave"];
            p["arDb"] = 6.0 * e["brilho"] / 100;
        }
        else if (s > 0)
        {
            var profile = options.Profile;
            double Dose(double g) => Clamp(g * (0.4 + 1.2 * s), 0, 1);
            if (G("dc") > 0 || profile == "archive") p["dc"] = 1;
            if (G("zumbido") >= 0.15) { p["zumbidoHz"] = hum; p["zumbido"] = Clamp(0.5 + 0.5 * G("zumbido"), 0, 1) * Math.Min(1, 0.5 + s); }
            if (G("estalos") >= 0.15) p["estalos"] = Dose(G("estalos")) * (profile == "archive" ? 1 : 0.8);
            if (G("recorte") >= 0.15) { p["recorte"] = Clamp(Dose(0.5 + 0.5 * G("recorte")), 0, 1); p["nivelDoRecorte"] = level; }
            if (G("ruido") >= 0.15)
            {
                var r = Clamp(G("ruido") * (0.3 + s), 0, 1);
                if (profile == "live") r *= 1 - 0.7 * options.PreserveAmbience / 100.0;
                if (profile == "archive") r = Math.Min(1, r * 1.2);
                p["ruido"] = r;
            }
            if (profile == "live") p["corteGraveHz"] = 35;
            if (profile == "archive") { p["corteGraveHz"] = 30; p["arDb"] = 1.5 * s; }
            if (profile == "lossy" && !options.Ai) warnings.Add("reparo_de_compressao_por_ia_desligado");
        }
        // Reparo de compressão por IA (Apollo): no Avançado, a etapa "compressao" dosa; nos outros
        // perfis, só com a IA ligada e no MP3 ou quando os agudos param cedo.
        AiStep? ai = null;
        var bandForCut = Get(d, "bandaHz");
        var cut = Math.Clamp(Math.Floor(((bandForCut > 0 ? bandForCut : 16000) - 3000) / 100 + 0.5) * 100, 4000, 16000);
        if (options.Profile == "manual")
        {
            if (options.Steps["compressao"] > 0) ai = new AiStep("apollo", Round4(options.Steps["compressao"] / 100.0), cut);
        }
        else if (options.Ai && s > 0 && (options.Profile == "lossy" || G("banda") >= 0.15))
        {
            ai = new AiStep("apollo", Round4(Math.Min(1, 0.4 + s)), cut);
        }
        foreach (var k in p.Keys.ToList()) p[k] = Round4(p[k]);
        var active = new List<(string, double)>();
        if (p["dc"] > 0) active.Add(("dc", 1));
        if (p["zumbido"] > 0) active.Add(("zumbido", p["zumbido"]));
        if (p["estalos"] > 0) active.Add(("estalos", p["estalos"]));
        if (p["recorte"] > 0) active.Add(("recorte", p["recorte"]));
        if (p["ruido"] > 0) active.Add(("ruido", p["ruido"]));
        if (p["corteGraveHz"] > 0) active.Add(("grave", p["corteGraveHz"]));
        if (p["arDb"] != 0) active.Add(("brilho", p["arDb"]));
        if (ai is not null) active.Insert(0, ("compressao", ai.Mix));
        var noChange = active.Count == 0;
        if (!noChange) p["tetoDb"] = -1;
        if (noChange) warnings.Add(options.Profile == "manual" || s == 0 ? "nada_ligado" : "sem_defeitos_suficientes");
        return new Plan(options.Profile, active, p, warnings, noChange, evaluation, ai);
    }

    private static readonly IReadOnlyDictionary<string, string> WarningTexts = new Dictionary<string, string>
    {
        ["o_diagnostico_nao_garante_a_origem"] = "O diagnóstico mede o arquivo; não garante como ele foi gravado ou comprimido.",
        ["banda_limitada"] = "Os agudos param cedo: o que a compressão ou a gravação cortou não volta com filtros.",
        ["quase_silencio"] = "O áudio é quase todo silêncio.",
        ["reparo_de_compressao_por_ia_desligado"] = "O reparo de compressão por IA (Apollo) está desligado: este perfil faz só a limpeza leve. Ligue a IA para refazer o que a compressão destruiu.",
        ["sem_defeitos_suficientes"] = "O diagnóstico não encontrou defeitos suficientes para recomendar uma restauração: o áudio sai como entrou.",
        ["nada_ligado"] = "Nenhuma etapa ligada: o áudio sai como entrou.",
    };

    public static string WarningText(string id) => WarningTexts.TryGetValue(id, out var t) ? t : id;

    public static string Summary(IReadOnlyDictionary<string, double> d)
    {
        var a = Evaluate(d);
        string Strength(double g) => g >= 0.66 ? "forte" : g >= 0.33 ? "médio" : "leve";
        var names = new Dictionary<string, string>
        {
            ["recorte"] = "Recorte", ["zumbido"] = $"Zumbido de {Get(d, "zumbidoHz"):0} Hz", ["ruido"] = "Chiado/ruído de fundo", ["estalos"] = "Estalos", ["dc"] = "Deslocamento DC",
        };
        var found = a.Recommendations.Select(id => $"{names[id]} ({Strength(a.Problems[id].Severity)})").ToList();
        var band = a.Problems["banda"].Severity >= 0.15 ? $" · Agudos até ~{Math.Round(Get(d, "bandaHz") / 1000, 1).ToString(System.Globalization.CultureInfo.InvariantCulture)} kHz" : "";
        return $"{(found.Count > 0 ? string.Join(" · ", found) : "Sem defeitos evidentes")}{band} · pico {Get(d, "picoDb").ToString("0.0", System.Globalization.CultureInfo.InvariantCulture)} dBFS · piso de ruído {Get(d, "pisoDeRuidoDb"):0} dB";
    }

    public static string StepLabel(string id) => Steps.FirstOrDefault(s => s.Id == id).Label ?? id;

    public static string OutputName(string name, string extension)
    {
        var baseName = Path.GetFileNameWithoutExtension(name);
        return $"{(string.IsNullOrEmpty(baseName) ? "audio" : baseName)}-restaurado.{extension}";
    }
}
