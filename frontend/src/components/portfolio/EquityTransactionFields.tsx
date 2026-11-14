import React from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { NumberField } from './BondTransactionFields.tsx';
import { SUPPORTED_CURRENCIES, type Currency } from '../../types/auth.ts';

interface EquityTransactionFieldsProps {
  quantity: string;
  onQuantityChange: (val: string) => void;
  price: string;
  onPriceChange: (val: string) => void;
  commission: string;
  onCommissionChange: (val: string) => void;
  currency: Currency;
  onCurrencyChange: (val: Currency) => void;
  loadingPrice: boolean;
}

export const EquityTransactionFields: React.FC<EquityTransactionFieldsProps> = ({
  quantity,
  onQuantityChange,
  price,
  onPriceChange,
  commission,
  onCommissionChange,
  currency,
  onCurrencyChange,
  loadingPrice,
}) => {
  const { t } = useTranslation();

  return (
    <>
      <div className="grid grid-cols-2 gap-3">
        <NumberField
          label={t('transactionModal.quantity')}
          value={quantity}
          onChange={onQuantityChange}
          step="any"
          min="0.0001"
          placeholder="10"
        />
        <NumberField
          label={
            <span className="flex justify-between items-center">
              <span>{t('transactionModal.price')} ({currency})</span>
              {loadingPrice && <Loader2 className="w-3 h-3 text-zinc-500 animate-spin" />}
            </span>
          }
          value={price}
          onChange={onPriceChange}
          step="any"
          min="0.0001"
          placeholder="120.50"
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <NumberField
          label={`${t('transactionModal.commission')} (${currency})`}
          value={commission}
          onChange={onCommissionChange}
          step="any"
          min="0"
          placeholder="0"
          required={false}
        />
        <div>
          <label className="block text-xs text-zinc-400 mb-1.5">{t('transactionModal.currency')}</label>
          <select
            value={currency}
            onChange={(e) => onCurrencyChange(e.target.value as Currency)}
            className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-xs text-zinc-100 font-mono focus:outline-none focus:border-zinc-600"
          >
            {SUPPORTED_CURRENCIES.map((cur) => (
              <option key={cur} value={cur}>
                {cur}
              </option>
            ))}
          </select>
        </div>
      </div>
    </>
  );
};
