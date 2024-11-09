import { useState, createContext, useContext } from "react";

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState("");

  const login = (user) => {
    setUser(user);
  };

  const logout = () => {
    setUser(null);
  };

  const tokenActive = (token) => {
    setToken(token);
  };

  return (
    <AuthContext.Provider value={{ user, login, logout, tokenActive, token }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  return useContext(AuthContext);
};
