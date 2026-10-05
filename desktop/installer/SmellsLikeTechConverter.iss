; Instalador do Smells Like Tech Converter Desktop
; Gerado com Inno Setup 6. Compile com installer\build-installer.ps1,
; que publica o app em Release antes de empacotar.

#define AppName "Smells Like Tech Converter"
#define AppShortName "SmellsLikeTechConverter"
#define AppPublisher "Smells Like Tech Informatica"
#define AppUrl "https://www.smellsliketech.com.br"
#define AppExe "SmellsLikeTechConverter.exe"

; A versao vem da linha de comando (/DAppVersion=x.y.z); 0.1.0 e o padrao.
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

; Pasta publicada pelo dotnet publish (padrao: ..\artifacts\app)
#ifndef PayloadDir
  #define PayloadDir "..\artifacts\app"
#endif

[Setup]
; GUID proprio do produto: nao reutilizar em outro aplicativo.
AppId={{8E5A6C21-4F3B-4B7E-9C1D-2A7F5D0B6E14}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
VersionInfoVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}
AppUpdatesURL={#AppUrl}
LicenseFile={#PayloadDir}\kokoro\Licenses\GPL-3.0.txt
InfoBeforeFile={#PayloadDir}\kokoro\Licenses\LICENSING.md

; O usuario escolhe entre instalar para todos (Program Files, com UAC)
; ou somente para ele (LocalAppData, sem elevacao).
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
AllowNoIcons=yes

OutputDir=..\artifacts\installer
OutputBaseFilename={#AppShortName}Setup
SetupIconFile=..\src\SmellsLikeTech.Converter.Desktop\Assets\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}

WizardStyle=modern
WizardImageFile=wizard-large.bmp
WizardSmallImageFile=wizard-small.bmp
WizardImageStretch=yes

Compression=lzma2/max
SolidCompression=yes
LZMANumBlockThreads=4

; Windows 10 2004 (build 19041) e o minimo do Windows App SDK.
MinVersion=10.0.19041
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

; Fecha o aplicativo em execucao ao atualizar, em vez de exigir reinicio.
CloseApplications=yes
RestartApplications=no
SetupMutex={#AppShortName}SetupMutex

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na area de trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "Licenses\*"; DestDir: "{app}\Licenses"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir o {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Somente o que o proprio instalador nao rastreia. Os arquivos do usuario em
; D:\SmellsLikeTechConverter (saidas, modelos, historico) nunca sao removidos.
Type: filesandordirs; Name: "{app}\Assets"
Type: dirifempty; Name: "{app}"

[Code]
// Aviso amigavel quando o FFmpeg nao esta no PATH: o produto instala sem ele,
// mas video, audio e transcricao dependem dele.
function FfmpegEncontrado(): Boolean;
var
  ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/C where ffmpeg >nul 2>&1', '',
    SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and (not WizardSilent()) and (not FfmpegEncontrado()) then
  begin
    MsgBox(
      'O FFmpeg nao foi encontrado no PATH deste computador.' + #13#10#13#10 +
      'O aplicativo instala e abre normalmente, mas as conversoes de video, ' +
      'audio e a transcricao so funcionam com o FFmpeg disponivel.' + #13#10#13#10 +
      'Instale o FFmpeg (winget install Gyan.FFmpeg) ou informe o caminho ' +
      'dele em Configuracoes > Motores.',
      mbInformation, MB_OK);
  end;
end;
