; Instalador do plugin do Smells Like Tech Converter.
;
; E um produto separado do aplicativo desktop: AppId proprio, pasta propria e
; desinstalacao propria. Quem instala o plugin nao instala o aplicativo, e vice-versa;
; os dois podem conviver na mesma maquina sem se atrapalhar.
;
; Compile com installer\build-plugin-installer.ps1.

#define AppName "Smells Like Tech Converter - Plugin"
#define AppShortName "SmellsLikeTechPlugin"
#define AppPublisher "Smells Like Tech Informatica"
#define AppUrl "https://www.smellsliketech.com.br"
#define AppExe "SmellsLikeTechPlugin.exe"
#define SiteUrl "https://converter.smellsliketech.com.br"

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

#ifndef PayloadDir
  #define PayloadDir "..\artifacts\plugin"
#endif

[Setup]
; GUID proprio: diferente do aplicativo desktop de proposito.
AppId={{C4A17D93-8E62-4A5F-B0D8-3F19E7C25A47}
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

; Um programa que roda em segundo plano nao deveria pedir administrador para nada.
; Instala so para o usuario atual, sem UAC.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Smells Like Tech\Plugin
DisableProgramGroupPage=yes
DisableDirPage=yes
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

MinVersion=10.0.19041
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

CloseApplications=yes
RestartApplications=no
SetupMutex={#AppShortName}SetupMutex

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "iniciarcomwindows"; Description: "Ligar o plugin junto com o Windows"; GroupDescription: "Como o plugin funciona:"

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "Licenses\*"; DestDir: "{app}\Licenses"; Flags: ignoreversion recursesubdirs

[Icons]
; Sem atalho na area de trabalho: o plugin nao e um programa que se "abre".
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"

[Registry]
; Inicio automatico no logon, sem servico do Windows e sem exigir administrador.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "SmellsLikeTechPlugin"; ValueData: """{app}\{#AppExe}"""; \
  Flags: uninsdeletevalue; Tasks: iniciarcomwindows

[Run]
Filename: "{app}\{#AppExe}"; Description: "Ligar o plugin agora"; Flags: nowait postinstall skipifsilent
Filename: "{#SiteUrl}"; Description: "Abrir o conversor no navegador"; Flags: nowait postinstall skipifsilent shellexec

[UninstallRun]
; Encerra o plugin antes de apagar os arquivos, senao o executavel fica travado.
Filename: "{cmd}"; Parameters: "/C taskkill /IM {#AppExe} /F"; Flags: runhidden; RunOnceId: "PararPlugin"

[UninstallDelete]
Type: filesandordirs; Name: "{app}\Assets"
Type: dirifempty; Name: "{app}"

[Code]
// O plugin so serve para as funcoes pesadas, e todas elas passam pelo FFmpeg.
// Sem ele o programa liga, mas nao entrega o que promete: melhor dizer na hora.
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
      'O plugin liga normalmente e o site vai reconhece-lo, mas video, audio e ' +
      'transcricao ficam indisponiveis ate o FFmpeg estar instalado.' + #13#10#13#10 +
      'Para instalar: winget install Gyan.FFmpeg',
      mbInformation, MB_OK);
  end;
end;
