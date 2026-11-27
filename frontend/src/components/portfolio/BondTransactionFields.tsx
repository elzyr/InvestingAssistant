import React from 'react';
import { useTranslation } from 'react-i18next';
import { Sparkles } from 'lucide-react';
import { BOND_FAMILY_SPECS } from '../../utils/domain/assets.ts';
import type { Currency } from '../../types/auth.ts';
import type { TransactionType } from '../../types/portfolio.ts';

interface BondMetadataFieldsProps {
  isEdit: boolean;
  transactionType: TransactionType;
  seriesName: string;
  onSeriesNameChange: (val: string) => void;
  interestType: 'FIXED' | 'INFLATION';
  onInterestTypeChange: (type: 'FIXED' | 'INFLATION') => void;
}

export const BondMetadataFields: React.FC<BondMetadataFieldsProps> = ({
  isEdit,
  transactionType,
  seriesName,
  onSeriesNameChange,
  interestType,
  onInterestTypeChange,
}) => {
  const { t } = useTranslation();

  return (
    <>
      <div>
        <div className="flex justify-between items-center mb-1.5">
          <label className="text-xs text-zinc-400">
            {t('transactionModal.seriesName')} {!isEdit && `(${t('transactionModal.optional')})`}
          </label>
          <span className="text-[10px] text-amber-400/90 font-mono flex items-center gap-1">
            <Sparkles className="w-3 h-3" /> {t('transactionModal.autoFill')}
          </span>
        </div>
        <input
          type="text"
          value={seriesName}
          onChange={(e) => onSeriesNameChange(e.target.value)}
          className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono uppercase focus:outline-none focus:border-zinc-600"
          placeholder={t('transactionModal.seriesPlaceholder')}
        />
        {!isEdit && (
          <span className="text-[10px] text-zinc-500 block mt-1">
            {t('transactionModal.seriesHint')}
          </span>
        )}
      </div>

      {transactionType === 'BUY' && (
        <div>
          <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.interestType')}</label>
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => onInterestTypeChange('FIXED')}
              className={`py-2 px-3 rounded text-xs font-semibold border transition ${
                interestType === 'FIXED'
                  ? 'bg-amber-950/60 border-amber-600 text-amber-300'
                  : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
              }`}
            >
              {t('transactionModal.fixed')}
            </button>
            <button
              type="button"
              onClick={() => onInterestTypeChange('INFLATION')}
              className={`py-2 px-3 rounded text-xs font-semibold border transition ${
                interestType === 'INFLATION'
                  ? 'bg-amber-950/60 border-amber-600 text-amber-300'
                  : 'bg-zinc-900 border-zinc-800 text-zinc-400 hover:border-zinc-700'
              }`}
            >
              {t('transactionModal.inflation')}
            </button>
          </div>
        </div>
      )}
    </>
  );
};

interface BondAmountsFieldsProps {
  isEdit: boolean;
  transactionType: TransactionType;
  quantity: string;
  onQuantityChange: (val: string) => void;
  price: string;
  onPriceChange: (val: string) => void;
  commission: string;
  onCommissionChange: (val: string) => void;
  currency: Currency;
  interestType: 'FIXED' | 'INFLATION';
  bondRate: string;
  onBondRateChange: (val: string) => void;
  bondMargin: string;
  onBondMarginChange: (val: string) => void;
  termMonths: string;
  onTermMonthsChange: (val: string) => void;
}

export interface NumberFieldProps {
  label: React.ReactNode;
  value: string;
  onChange: (val: string) => void;
  step: string;
  min: string;
  max?: string;
  placeholder: string;
  required?: boolean;
  hint?: string;
}

const INPUT_CLASS =
  'w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600';

export const NumberField: React.FC<NumberFieldProps> = ({
  label,
  value,
  onChange,
  step,
  min,
  max,
  placeholder,
  required = true,
  hint,
}) => (
  <div>
    <label className="block text-xs text-zinc-400 mb-1.5">{label}</label>
    <input
      type="number"
      step={step}
      min={min}
      max={max}
      required={required}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={INPUT_CLASS}
      placeholder={placeholder}
    />
    {hint && <span className="text-[10px] text-zinc-500 font-mono block mt-1">{hint}</span>}
  </div>
);

export const BondAmountsFields: React.FC<BondAmountsFieldsProps> = ({
  isEdit,
  transactionType,
  quantity,
  onQuantityChange,
  price,
  onPriceChange,
  commission,
  onCommissionChange,
  currency,
  interestType,
  bondRate,
  onBondRateChange,
  bondMargin,
  onBondMarginChange,
  termMonths,
  onTermMonthsChange,
}) => {
  const { t, i18n } = useTranslation();
  const parsedQty = parseFloat(quantity) || 0;
  const bondTotalNominal = parsedQty * 100;
  const unitCurrency = currency || 'PLN';

  if (transactionType === 'SELL') {
    return (
      <>
        <div className="grid grid-cols-2 gap-3">
          <NumberField
            label={t('transactionModal.sellQuantity')}
            value={quantity}
            onChange={onQuantityChange}
            step="1"
            min="1"
            placeholder="10"
          />
          <NumberField
            label={t('transactionModal.sellPrice')}
            value={price}
            onChange={onPriceChange}
            step="0.01"
            min="0"
            placeholder="100.00"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.totalPayout')}</label>
            <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-emerald-400 font-mono flex items-center h-[34px]">
              {(parsedQty * (parseFloat(price) || 0)).toLocaleString(i18n.language, { minimumFractionDigits: 2 })} {unitCurrency}
            </div>
          </div>
          <NumberField
            label={t('transactionModal.commissionFee')}
            value={commission}
            onChange={onCommissionChange}
            step="0.01"
            min="0"
            placeholder="0.00"
            required={false}
          />
        </div>
      </>
    );
  }

  return (
    <>
      <div className="grid grid-cols-2 gap-3">
        <NumberField
          label={t('transactionModal.nominalUnits')}
          value={quantity}
          onChange={onQuantityChange}
          step="1"
          min="1"
          placeholder="10"
          hint={!isEdit ? t('transactionModal.nominalPriceNote') : undefined}
        />
        <div>
          <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.nominalValue')}</label>
          <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-emerald-400 font-mono flex items-center h-[34px]">
            {bondTotalNominal.toLocaleString(i18n.language, { minimumFractionDigits: 2 })} {unitCurrency}
          </div>
          {!isEdit && (
            <span className="text-[10px] text-zinc-500 font-mono block mt-1">
              {t('transactionModal.amountToPay')}
            </span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <NumberField
          label={interestType === 'INFLATION' ? t('transactionModal.bondRateFirstYear') : t('transactionModal.bondRateAnnual')}
          value={bondRate}
          onChange={onBondRateChange}
          step="0.01"
          min="0"
          placeholder="6.50"
        />
        {interestType === 'INFLATION' ? (
          <NumberField
            label={t('transactionModal.bondMargin')}
            value={bondMargin}
            onChange={onBondMarginChange}
            step="0.01"
            min="0"
            placeholder="1.25"
          />
        ) : (
          <div>
            <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.couponType')}</label>
            <div className="w-full bg-zinc-900/60 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-400 font-sans flex items-center h-[34px]">
              {t('transactionModal.fixedRate')}
            </div>
          </div>
        )}
      </div>

      <div>
        <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.termMonths')}</label>
        <select
          value={termMonths || '36'}
          onChange={(e) => onTermMonthsChange(e.target.value)}
          className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-sans focus:outline-none focus:border-zinc-600"
        >
          {BOND_FAMILY_SPECS.map((spec) => (
            <option key={spec.tenureMonths} value={String(spec.tenureMonths)}>
              {t(spec.termOptionKey)}
            </option>
          ))}
        </select>
      </div>
    </>
  );
};
