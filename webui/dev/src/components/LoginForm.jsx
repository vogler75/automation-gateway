import React, { useState, useEffect, useContext, createContext } from "react";
import { getToken } from "../api/getToken";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../utils/auth";
import { AlertDismissible } from "./AlertDismissible";

const MyTokenContext = createContext();

export function LoginForm() {
  const navigate = useNavigate();
  const [token, setToken] = useState("");
  const auth = useAuth();
  const [user, setUser] = useState("");
  const [showAlert, setShowAlert] = useState(false);
  const [endpoint, setEndpoint] = useState("");

  useEffect(() => {
    const savedEndpoint = localStorage.getItem("serverEndpoint") || "localhost";
    setEndpoint(savedEndpoint);
  }, []);

  const handleEndpointChange = (e) => {
    const endpointValue = e.target.value;
    setEndpoint(endpointValue);
    localStorage.setItem("serverEndpoint", endpointValue);
  };

  const serverURL = `http://${endpoint}:9999`;

  useEffect(() => {}, [serverURL]);

  async function onClick() {
    const response = await getToken();
    if (user !== "") {
      if (response.data.createAccessToken.ok === true) {
        setToken(response.data.createAccessToken.message);
        auth.tokenActive(
          JSON.stringify(response.data.createAccessToken.message)
        );
        auth.login(user);
        navigate("WBM", { replace: true });
      } else {
        setShowAlert(true);
      }
    } else {
      setShowAlert(true);
    }
  }

  const closeAlert = () => {
    setShowAlert(false);
  };

  const onSubmit = async (e) => {
    e.preventDefault();
    await onClick();
  };

  return (
    <MyTokenContext.Provider value={{ token, setToken }}>
      <form onSubmit={onSubmit}>
        <div
          style={{
            textAlign: "center",
            fontSize: "2rem",
            fontWeight: "bold",
            margin: "20px 0",
          }}
        >
          <label>Login information</label>
        </div>
        <br />

        <div className="mb-3">
          <input
            type="text"
            className="form-control"
            placeholder="Server IP or endpoint (e.g., localhost or 192.168.1.1)"
            value={endpoint}
            onChange={handleEndpointChange}
          />
        </div>

        <div className="mb-3">
          <input
            type="username"
            className="form-control"
            placeholder="Username"
            onChange={(e) => setUser(e.target.value)}
          />
        </div>
        <div className="mb-3">
          <input
            type="password"
            className="form-control"
            placeholder="Password"
          />
        </div>

        <button type="submit" className="btn btn-success" onClick={onClick}>
          Login
        </button>
      </form>
      <AlertDismissible show={showAlert} onClose={closeAlert} />
    </MyTokenContext.Provider>
  );
}

export const useMyToken = () => useContext(MyTokenContext);
export default MyTokenContext;
