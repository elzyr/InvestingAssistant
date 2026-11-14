import type { InstrumentResponse } from '../../types/instrument.ts';

export interface BondFamilySpec {
  family: string;
  tenureMonths: number;
  interestType: 'FIXED' | 'INFLATION';
  nameKey: string;
  descriptionKey: string;
  termOptionKey: string;
}

export const BOND_FAMILY_SPECS: readonly BondFamilySpec[] = [
  {
    family: 'OTS',
    tenureMonths: 3,
    interestType: 'FIXED',
    nameKey: 'dashboard.threeMonth',
    descriptionKey: 'dashboard.fixedRate',
    termOptionKey: 'transactionModal.termMonthsOptionOTS',
  },
  {
    family: 'ROR',
    tenureMonths: 12,
    interestType: 'FIXED',
    nameKey: 'dashboard.oneYear',
    descriptionKey: 'dashboard.variableRate',
    termOptionKey: 'transactionModal.termMonthsOptionROR',
  },
  {
    family: 'DOR',
    tenureMonths: 24,
    interestType: 'FIXED',
    nameKey: 'dashboard.twoYear',
    descriptionKey: 'dashboard.variableRate',
    termOptionKey: 'transactionModal.termMonthsOptionDOR',
  },
  {
    family: 'TOS',
    tenureMonths: 36,
    interestType: 'FIXED',
    nameKey: 'dashboard.threeYear',
    descriptionKey: 'dashboard.fixedRate',
    termOptionKey: 'transactionModal.termMonthsOptionTOS',
  },
  {
    family: 'COI',
    tenureMonths: 48,
    interestType: 'INFLATION',
    nameKey: 'dashboard.fourYear',
    descriptionKey: 'dashboard.inflationIndexed',
    termOptionKey: 'transactionModal.termMonthsOptionCOI',
  },
  {
    family: 'ROS',
    tenureMonths: 72,
    interestType: 'INFLATION',
    nameKey: 'dashboard.sixYear',
    descriptionKey: 'dashboard.inflationIndexed',
    termOptionKey: 'transactionModal.termMonthsOptionROS',
  },
  {
    family: 'EDO',
    tenureMonths: 120,
    interestType: 'INFLATION',
    nameKey: 'dashboard.tenYear',
    descriptionKey: 'dashboard.antiInflation',
    termOptionKey: 'transactionModal.termMonthsOptionEDO',
  },
  {
    family: 'ROD',
    tenureMonths: 144,
    interestType: 'INFLATION',
    nameKey: 'dashboard.twelveYear',
    descriptionKey: 'dashboard.inflationIndexed',
    termOptionKey: 'transactionModal.termMonthsOptionROD',
  },
] as const;

export const BOND_SPEC_BY_FAMILY: Record<string, BondFamilySpec> = Object.fromEntries(
  BOND_FAMILY_SPECS.map((spec) => [spec.family, spec])
);

export const BOND_SPEC_BY_TERM: Record<number, BondFamilySpec> = Object.fromEntries(
  BOND_FAMILY_SPECS.map((spec) => [spec.tenureMonths, spec])
);

export function isInflationBondFamily(family?: string | null): boolean {
  return family ? BOND_SPEC_BY_FAMILY[family.toUpperCase()]?.interestType === 'INFLATION' : false;
}

export function findKnownBondFamily(text?: string | null): string | null {
  if (!text) return null;
  const upper = text.toUpperCase();
  return BOND_FAMILY_SPECS.find((spec) => upper.includes(spec.family))?.family ?? null;
}

export function isBondInstrument(inst?: { type?: string; symbol?: string } | null): boolean {
  if (!inst) return false;
  return inst.type === 'BOND' || Boolean(inst.symbol?.startsWith('PL_BOND'));
}

export function isBondItem(
  item: {
    bondRate?: number | null;
    seriesName?: string | null;
    currency?: string;
    price?: number;
    averageBuyPrice?: number;
  },
  inst?: InstrumentResponse
): boolean {
  if (inst?.type === 'ETF' || inst?.type === 'STOCK') {
    return false;
  }
  return Boolean(
    item.bondRate != null ||
      (item.seriesName && item.seriesName.length >= 3) ||
      (inst &&
        (inst.type === 'BOND' ||
          inst.symbol.startsWith('PL_BOND') ||
          BOND_FAMILY_SPECS.some((spec) => inst.symbol.includes(spec.family))))
  );
}

export function parseBondSeriesMetadata(val: string): {
  tenure: number;
  interestType: 'FIXED' | 'INFLATION';
  issueDate: string;
} | null {
  const match = val.toUpperCase().trim().match(/^([A-Z]{3})(\d{2})(\d{2})$/);
  if (!match) return null;

  const [, prefix, monthStr, yearStr] = match;
  const spec = BOND_SPEC_BY_FAMILY[prefix];
  if (!spec) return null;

  const month = Math.min(12, Math.max(1, parseInt(monthStr, 10)));
  const totalMaturityMonths = (2000 + parseInt(yearStr, 10)) * 12 + (month - 1);
  const totalIssueMonths = totalMaturityMonths - spec.tenureMonths;
  const issueYear = Math.floor(totalIssueMonths / 12);
  const issueMonth = (totalIssueMonths % 12) + 1;
  const mm = String(issueMonth).padStart(2, '0');

  return {
    tenure: spec.tenureMonths,
    interestType: spec.interestType,
    issueDate: `${issueYear}-${mm}-01`,
  };
}
