using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>Projeto portátil com mídias. Extração restrita aos nomes gerados pelo aplicativo.</summary>
public static class StudioArchive
{
    private const long Limit=4L*1024*1024*1024;
    private static bool Safe(string s)=>Regex.IsMatch(s,"^midias/[0-9]{3}\\.[a-z0-9]{1,10}$");
    public static void Save(StudioProject p,Stream output)
    {
        p=StudioCreation.Upgrade(p);
        var problems=StudioRules.Problems(p);if(problems.Count>0) throw new ConversionException("invalid_project",string.Join(" ",problems));
        var map=new Dictionary<string,string>(StringComparer.Ordinal);
        using var zip=new ZipArchive(output,ZipArchiveMode.Create,true);
        long total=0;
        foreach(var path in StudioCreation.Files(p)) {
            var file=new FileInfo(path);if(!file.Exists) throw new ConversionException("missing_media","Uma mídia do projeto não está disponível.");
            total+=file.Length;if(total>Limit||map.Count>=511) throw new ConversionException("large_project","Use até 4 GB e 511 mídias por projeto.");
            var ext=Regex.Replace(file.Extension.TrimStart('.').ToLowerInvariant(),"[^a-z0-9]","");if(ext.Length==0) ext="bin";ext=ext[..Math.Min(10,ext.Length)];
            var name=$"midias/{map.Count:000}.{ext}";map.Add(path,name);
            using var source=file.OpenRead();using var target=zip.CreateEntry(name,CompressionLevel.NoCompression).Open();source.CopyTo(target);
        }
        using var writer=new StreamWriter(zip.CreateEntry("projeto.json",CompressionLevel.NoCompression).Open(),new UTF8Encoding(false));
        writer.Write(StudioCreation.MapFiles(StudioCreation.Upgrade(p),path=>map[path]).ToJson());
    }
    /// <summary>O chamador fornece uma pasta nova. Em falha, ela é removida sem tocar no projeto atual.</summary>
    public static StudioProject Open(Stream input,string directory)
    {
        if(Directory.Exists(directory)) throw new IOException("Escolha uma pasta nova para abrir o projeto.");
        Directory.CreateDirectory(directory);
        try {
            using var zip=new ZipArchive(input,ZipArchiveMode.Read,true);
            if(zip.Entries.Count>512||zip.Entries.Sum(e=>e.Length)>Limit||zip.Entries.Select(e=>e.FullName).Distinct(StringComparer.Ordinal).Count()!=zip.Entries.Count
                ||zip.Entries.Any(e=>e.FullName!="projeto.json"&&!Safe(e.FullName))) throw new IOException("Projeto inválido ou grande demais.");
            var json=zip.GetEntry("projeto.json");if(json is null||json.Length>4*1024*1024) throw new IOException("O arquivo não contém um projeto válido.");
            StudioProject p;using(var reader=new StreamReader(json.Open())) p=StudioProject.Parse(reader.ReadToEnd());
            var problems=StudioRules.Problems(p);if(problems.Count>0) throw new IOException(string.Join(" ",problems));
            var map=new Dictionary<string,string>(StringComparer.Ordinal);
            Directory.CreateDirectory(Path.Combine(directory,"midias"));
            foreach(var path in StudioCreation.Files(p)) {
                if(!Safe(path)||zip.GetEntry(path) is not {} entry) throw new IOException("Uma mídia está ausente no projeto salvo.");
                var target=Path.Combine(directory,path.Replace('/',Path.DirectorySeparatorChar));
                using(var source=entry.Open()) using(var file=File.Create(target)) source.CopyTo(file);
                map.Add(path,target);
            }
            return StudioCreation.MapFiles(StudioCreation.Upgrade(p),path=>map[path]);
        } catch {Directory.Delete(directory,true);throw;}
    }
}
