export declare function dataEmSaoPaulo(instante?: Date): { ano: number; mes: number; dia: number };
export declare function semanaIso(ano: number, mes: number, dia: number): string;
export declare function semanaDeSaoPaulo(instante?: Date): string;
export declare function conferirCriacaoMusical(
  plano: "gratis" | "pro",
  dados: { projetoId: string; criadosNaSemana?: string[] },
): { contador: "musica"; mensagem: string } | null;
