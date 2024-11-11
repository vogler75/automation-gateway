import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App.jsx";
import "bootstrap/dist/css/bootstrap.css";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { RequireAuth } from "./utils/RequireAuth.jsx";
import { Home } from "./pages/Home.jsx";
import { AuthProvider } from "./utils/auth.jsx";
import { DriversMqtt } from "./pages/DriversMqtt.jsx";
import { DriversOpcua } from "./pages/DriversOpcua.jsx";
import { LoggersMqtt } from "./pages/LoggersMqtt.jsx";
import { ServersMqtt } from "./pages/ServersMqtt.jsx";
import { ServersGQL } from "./pages/ServersGQL.jsx";
import { Dashboard } from "./pages/Dashboard.jsx";
import { LoggersInfluxDB } from "./pages/LoggersInfluxDB.jsx";
import { LoggersQuestDB } from "./pages/LoggersQuestDB.jsx";
import { LoggersJDBC } from "./pages/LoggersJDBC.jsx";
import { ServersOpcua } from "./pages/ServersOpcua.jsx";
import { Settings } from "./pages/Settings.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<App />}></Route>
          <Route
            path="WBM"
            element={
              <RequireAuth>
                <Home />
              </RequireAuth>
            }
          >
            <Route index element={<Dashboard />} />
            <Route path="11" element={<DriversMqtt />} />
            <Route path="12" element={<DriversOpcua />} />
            <Route path="13" element={<div>Drivers PLC4X</div>} />
            <Route path="21" element={<ServersMqtt />} />
            <Route path="22" element={<ServersGQL />} />
            <Route path="23" element={<ServersOpcua />} />
            <Route path="31" element={<LoggersMqtt />} />
            <Route path="32" element={<LoggersInfluxDB />} />
            <Route path="33" element={<div>Loggers IoTDB</div>} />
            <Route path="34" element={<div>Loggers Kafka</div>} />
            <Route path="35" element={<LoggersJDBC />} />
            <Route path="36" element={<LoggersQuestDB />} />
            <Route path="37" element={<div>Loggers Neo4J</div>} />
            <Route path="4" element={<Settings />} />
            <Route path="5" element={<div>Logout</div>} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  </React.StrictMode>
);
