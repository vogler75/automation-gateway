import { createContext, useContext } from "react";

export const PageTitleContext = createContext(null);

export function usePageTitle() {
  return useContext(PageTitleContext);
}
